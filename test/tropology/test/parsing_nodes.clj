(ns tropology.test.parsing-nodes
  (:require [clojure.test :refer :all]
            [taoensso.timbre.profiling :as prof]
            [tropology.test.db-nodes :as tdb]
            [tropology.test.parsing :as tp]
            [korma.core :refer [select where]]
            [tropology.parsing :refer :all]
            [tropology.db :as db]
            [tropology.base :as b]
            [clojure.string :as s]))


;
; Exception logging
;


(deftest test-log-exception
  (tdb/wipe-test-db)
  (let [url           "http://tvtropes.org/pmwiki/pmwiki.php/Anime/CowboyBebop"
        original-data (-> (node-data-from-url url) (assoc :is-redirect true)) ; Create a redirect node
        original-node (tdb/create-node! original-data)
        t             (Throwable. "Oopsy")
        logged-node   (log-node-exception! url t)]
    (println "Yes, we were supposed to get an exception logged up there. ⤴")
    (is (not= logged-node nil))
    (is (:is-redirect original-node))                       ; We did create a redirect node
    (is (nil? (:error original-node)))
    (is (:has-error logged-node))
    (is (= "Oopsy" (:error logged-node)))))




;
; Parsing
;


(deftest test-record-page-local
  ; Note that we load the page locally, so that we can pass a file that we
  ; know how may URLs it's supposed to include, while adding a provenance
  ; URL so that parsing and tagging takes place as expected.
  (tdb/wipe-test-db)
  (->>
    (let [path  (str tp/test-file-path "CowboyBebop.html")
          _     (record-page! path "http://tvtropes.org/pmwiki/pmwiki.php/Anime/CowboyBebop")
          saved (tdb/get-all-articles)
          rels  (tdb/get-all-article-rels)
          html  (db/get-html "anime/cowboybebop")
          item  (db/query-by-code "anime/cowboybebop")]
      (is (= 317565 (count html)))
      (is (= 650 (count saved)))
      (is (= 650 (count rels)))
      (doseq [node saved]
        (let [values (select-keys node [:outgoing :incoming])]
          (if (= "anime/cowboybebop" (:code node))
            (is (= {:outgoing 650 :incoming 1} values))     ; The page links to itself. Consider removing that.
            (is (= {:outgoing 0 :incoming 1} values))
            )))
      (is (.startsWith (:description item) "Immodestly billed as")))
    (prof/profile :trace :Database)
    ))

(deftest test-import-page-set-manga
  ; Test with the Manga page, mostly for profiling reasons, since it'll require
  ; creating 1880 articles, assign 1880 labels and create 1879 relationships.
  (tdb/wipe-test-db)
  (->>
    (let [to-import {:url        (str tp/test-file-path "Manga.html")
                     :provenance "http://tvtropes.org/pmwiki/pmwiki.php/Main/Manga"}
          _         (record-page! (:url to-import) (:provenance to-import))
          all-nodes (tdb/get-all-articles)
          all-rels  (tdb/get-all-article-rels)
          ]
      (is (= 1880 (count all-nodes)))
      (is (= 1879 (count all-rels)))
      )
    (prof/profile :trace :Database)
    ))

(deftest test-import-page-error
  ; Guess which import will fail with an endless redirect?
  (tdb/wipe-test-db)
  (->>
    (let [_         (record-page! "http://tvtropes.org/pmwiki/pmwiki.php/Main/CircularRedirect")
          all-nodes (tdb/get-all-articles)
          all-rels  (tdb/get-all-article-rels)
          node      (first all-nodes)
          ]
      (println "Yes, we were supposed to get an exception logged up there. ⤴")
      (is (= 1 (count all-nodes)))
      (is (= 0 (count all-rels)))
      (is (= "main/circularredirect" (:code node)))
      (is (:has-error node))
      )
    (prof/profile :trace :Database)
    ))

(deftest test-import-lot
  ; Slow test, mostly here for profiling reasons and to see if we get any clashes
  (tdb/wipe-test-db)
  (->>
    (let [to-import [{:url        (str tp/test-file-path "Manga.html")
                      :provenance "http://tvtropes.org/pmwiki/pmwiki.php/Main/Manga"}
                     {:url        (str tp/test-file-path "Anime.html")
                      :provenance "http://tvtropes.org/pmwiki/pmwiki.php/Main/Anime?from=Main.AnimeAndManga"}
                     {:url        (str tp/test-file-path "FunnyFilm.html")
                      :provenance "http://tvtropes.org/pmwiki/pmwiki.php/Funny/Film"}
                     {:url        (str tp/test-file-path "Actors.html")
                      :provenance "http://tvtropes.org/pmwiki/pmwiki.php/Main/Actors"}
                     {:url        (str tp/test-file-path "FilmsOfThe1990s.html")
                      :provenance "http://tvtropes.org/pmwiki/pmwiki.php/Main/FilmsOfThe1990s"}
                     {:url        (str tp/test-file-path "ComedyTropes.html")
                      :provenance "http://tvtropes.org/pmwiki/pmwiki.php/Main/ComedyTropes?from=Main.ComedyTrope"}
                     {:url        (str tp/test-file-path "SignatureSong.html")
                      :provenance "http://tvtropes.org/pmwiki/pmwiki.php/Main/SignatureSong"}
                     {:url        (str tp/test-file-path "NamesToKnowInAnime.html")
                      :provenance "http://tvtropes.org/pmwiki/pmwiki.php/Main/NamesToKnowInAnime?from=Main.JapaneseVoiceActors"}
                     {:url        (str tp/test-file-path "WesternAnimation.html")
                      :provenance "http://tvtropes.org/pmwiki/pmwiki.php/Main/WesternAnimation"}
                     {:url        (str tp/test-file-path "AmericanSeries.html")
                      :provenance "http://tvtropes.org/pmwiki/pmwiki.php/Main/AmericanSeries"}
                     ]
          ; We can't pmap these yet, as transactions abort while waiting on a lock
          _         (doall (map #(record-page! (:url %) (:provenance %)) to-import))
          all-nodes (tdb/get-all-articles)
          all-rels  (tdb/get-all-article-rels)
          to-review (db/query-for-codes ["Main/Manga" "Funny/Film" "Main/HumorDissonance" "Creator/TomokazuSugita"])
          ]
      (is (= 14983 (count all-nodes)))
      (is (= 16192 (count all-rels)))
      (are [code incoming outgoing] (first (filter #(and
                                                     (= incoming (:incoming %))
                                                     (= outgoing (:outgoing %))
                                                     (= code (:code %))) to-review))
                                    "main/manga" 1 1879
                                    "funny/film" 0 1543
                                    "main/humordissonance" 1 0
                                    "creator/tomokazusugita" 1 0)
      (is (= 10 (count (tdb/get-all-contents))))
      (are [code len] (= len (count (db/get-html code)))
                      "main/manga" 411644
                      "main/anime" 442350
                      "funny/film" 433272
                      "main/actors" 378801
                      "main/filmsofthe1990s" 441995
                      "main/comedytropes" 335442
                      "main/signaturesong" 528673
                      "main/namestoknowinanime" 394792
                      "main/westernanimation" 472089
                      "main/americanseries" 328855
                      )
      )
    (prof/profile :trace :test-import-lot)))


(deftest test-import-retains-display
  ; Ensure that if page B references page A, and A already exists in the system when B is
  ; imported, the display value does not end up all in lowercase.
  (tdb/wipe-test-db)
  (testing "Display is initially lowercase"
    ; The og:url for the Anime.html file contains a query. This test helps us
    ; ensure that the node is NOT recorded as a redirect when the provencance
    ; does not exactly match the URL
    (let [_    (record-page! (str tp/test-file-path "Anime.html") "http://tvtropes.org/pmwiki/pmwiki.php/main/anime")
          recs (db/query-for-codes ["main/anime" "anime/cowboybebop"])]
      (are [code display] (first (filter #(and (= code (:code %)) (= display (:display %))) recs))
                          "main/anime" "Main/Anime"
                          "anime/cowboybebop" "anime/cowboybebop" ; Display comes from URL, which is lower case
                          )))
  (testing "Display is remains upper case when importing reference"
    (let [_    (record-page! (str tp/test-file-path "CowboyBebop.html") "http://tvtropes.org/pmwiki/pmwiki.php/anime/cowboybebop")
          recs (db/query-for-codes ["main/anime" "anime/cowboybebop"])]
      (are [code display] (first (filter #(and (= code (:code %)) (= display (:display %))) recs))
                          "main/anime" "Main/Anime"         ; Display isn't overridden from the lowercase url
                          "anime/cowboybebop" "Anime/CowboyBebop"
                          )))
  )

(deftest test-record-page-live
  ; Load a live page. I don't check for how many nodes we're saving since that can
  ; and will change along with the page.
  (tdb/wipe-test-db)
  (let [_     (record-page! "http://tvtropes.org/pmwiki/pmwiki.php/Main/WesternAnimation")
        saved (tdb/get-all-articles)
        node  (db/query-by-code "Main/WesternAnimation")]
    (is (< 0 (count saved)))
    (are [property value] (= value (node property))
                          :has-error false
                          :category "main"
                          :code "main/westernanimation"
                          :display "Main/WesternAnimation"
                          :is-redirect false
                          :url "http://tvtropes.org/pmwiki/pmwiki.php/Main/WesternAnimation")
    ))

(deftest test-record-page-same-provenance
  ; See note on test-record-page-local about the provenance URL
  (tdb/wipe-test-db)
  (->>
    (let [path    (str tp/test-file-path "TakeMeInstead-pruned.html")
          _       (record-page! path "http://tvtropes.org/pmwiki/pmwiki.php/Main/TakeMeInstead")
          saved   (tdb/get-all-articles)
          node    (db/query-by-code "Main/TakeMeInstead")
          ignored (db/query-by-code "External/LinkOutsideWikiText")
          ]
      (is (= (count saved) 5))
      (is (nil? ignored))
      (are [property value] (= value (node property))
                            :has-error false
                            :category "main"
                            :code "main/takemeinstead"
                            :display "Main/TakeMeInstead"
                            :is-redirect false
                            :url "http://tvtropes.org/pmwiki/pmwiki.php/Main/TakeMeInstead")
      )
    (prof/profile :trace :Database)
    ))


(deftest test-query-available-after-import
  ; See note on test-record-page-local about the provenance URL
  (tdb/wipe-test-db)
  (->>
    (let [path     (str tp/test-file-path "TakeMeInstead-pruned.html")
          _        (record-page! path "http://tvtropes.org/pmwiki/pmwiki.php/Main/TakeMeInstead")
          saved    (tdb/get-all-articles)
          to-query (db/query-nodes-to-crawl)
          ]
      (is (= 5 (count saved)))
      (is (= 4 (count to-query)))
      (doseq [i to-query]
        (is (is-valid-url? i)))
      (are [code] (first (filter #(= code (b/code-from-url %)) to-query))
                  "main/zeerust"
                  "main/tobecontinued"
                  "main/bishiesparkle"
                  "main/denserandwackier"
                  ))
    (prof/profile :trace :Database)
    ))

(deftest test-query-for-codes
  ; Test if we can retrieve a group of codes after importing a page
  ;
  ; While maybe this would be suited to a simpler test on db-nodes,
  ; we want to be able to test against something closer to real data
  (tdb/wipe-test-db)
  (->>
    (let [path     (str tp/test-file-path "TakeMeInstead-pruned.html")
          _        (record-page! path "http://tvtropes.org/pmwiki/pmwiki.php/Main/TakeMeInstead")
          to-query ["Main/TakeMeInstead" "Main/ToBeContinued" "Main/DenserAndWackier"]
          queried  (db/query-for-codes to-query)
          ]
      (is (= 3 (count queried)))
      (doseq [code to-query]
        (is (not-empty (filter #(= (s/lower-case code) (:code %)) queried)))))
    (prof/profile :trace :Database)
    ))


(deftest test-record-page-twice
  ; See note on test-record-page-local about the provenance URL
  ;
  ; On this test I query for the links directly using cypher instead of our
  ; helper functions since those already do DISTINCT and would not return
  ; duplicates.
  (tdb/wipe-test-db)
  (let [path  (str tp/test-file-path "TakeMeInstead-pruned.html")
        _     (record-page! path "http://tvtropes.org/pmwiki/pmwiki.php/Main/TakeMeInstead")
        saved (tdb/get-all-articles)
        _     (record-page! path "http://tvtropes.org/pmwiki/pmwiki.php/Main/TakeMeInstead")
        again (tdb/get-all-articles)
        links (select db/links (where {:from-code "main/takemeinstead"}))
        html  (first (tdb/get-all-contents))
        ]
    (is (= 5 (count saved)))                                ; There's only five links on the file
    (is (= 5 (count again)))                                ; Same number of links is returned the second time
    (is (= 4 (count links)))                                ; No duplicated links are created
    (is (= 9381 (count (:html html))))
    ))

(deftest test-record-page-with-redirect
  ; On this test we pass a provenance URL which will differ from the page's og:url
  ; so that we can test that:
  ; - A node is created for the target page that we got redirected to and obtained the data from
  ; - All links are created from the id we got redirected to
  ; - There is a node created for the original URL and tagged as being a redirect
  ; - There are no links created from the original redirector
  (tdb/wipe-test-db)
  (let [path        (str tp/test-file-path "TakeMeInstead-pruned.html")
        _           (record-page! path "http://tvtropes.org/pmwiki/pmwiki.php/Main/TakeMeInsteadRedirector")
        main-node   (db/query-by-code "Main/TakeMeInstead")
        redir-node  (db/query-by-code "Main/TakeMeInsteadRedirector")
        links-main  (db/query-from "Main/TakeMeInstead" :LINKSTO)
        links-redir (db/query-from "Main/TakeMeInsteadRedirector" :LINKSTO)
        ]
    (are [property value] (= value (main-node property))
                          :has-error false
                          :category "main"
                          :code "main/takemeinstead"
                          :display "Main/TakeMeInstead"
                          :is-redirect false
                          :redirects-to nil
                          :url "http://tvtropes.org/pmwiki/pmwiki.php/Main/TakeMeInstead")
    (are [property value] (= value (redir-node property))
                          :has-error false
                          :code "main/takemeinsteadredirector"
                          :is-redirect true
                          :display "Main/TakeMeInsteadRedirector"
                          :category "main"
                          :redirects-to "main/takemeinstead"
                          :url "http://tvtropes.org/pmwiki/pmwiki.php/Main/TakeMeInsteadRedirector"
                          )
    (is (empty? links-redir))
    (is (= 4 (count links-main)))
    ))


(deftest test-redirect-case-sensitivity
  ; On this test we pass a provenance URL which will differ from the page's og:url,
  ; but just in that the URL's case is not the same. It should not be marked as a
  ; redirect.
  (tdb/wipe-test-db)
  (let [path       (str tp/test-file-path "TakeMeInstead-pruned.html")
        _          (record-page! path "http://tvtropes.org/pmwiki/pmwiki.php/main/takeMEinstEAD")
        main-node  (db/query-by-code "main/takemeinstead")
        links-main (db/query-from "Main/TakeMeInstead" :LINKSTO)
        ]
    (are [property value] (= value (main-node property))
                          :has-error false
                          :category "main"
                          :code "main/takemeinstead"
                          :display "Main/TakeMeInstead"
                          :is-redirect false
                          :url "http://tvtropes.org/pmwiki/pmwiki.php/Main/TakeMeInstead")
    (is (= 4 (count links-main)))
    ))

(deftest test-redirect-ignores-query
  ; We now pass a provenance that has a query. TVTropes sometimes does this
  ; to indicate that a page is being redirected to from another one, but
  ; all it actually cares about is the root path.
  ;
  ; Queries should be ignored for redirect purposes.
  (tdb/wipe-test-db)
  (let [path       (str tp/test-file-path "TakeMeInstead-pruned.html")
        _          (record-page! path "http://tvtropes.org/pmwiki/pmwiki.php/main/takemeinstead?a=1")
        main-node  (db/query-by-code "main/takemeinstead")
        links-main (db/query-from "Main/TakeMeInstead" :LINKSTO)
        ]
    (are [property value] (= value (main-node property))
                          :has-error false
                          :category "main"
                          :code "main/takemeinstead"
                          :display "Main/TakeMeInstead"
                          :is-redirect false
                          :url "http://tvtropes.org/pmwiki/pmwiki.php/Main/TakeMeInstead")
    (is (= 4 (count links-main)))
    ))


