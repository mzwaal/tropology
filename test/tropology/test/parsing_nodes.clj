(ns tropology.test.parsing-nodes
  (:require [clojure.test :refer :all]
            [clojurewerkz.neocons.rest.cypher :as cy]
            [taoensso.timbre.profiling :as prof]
            [tropology.test.db-nodes :as tdb]
            [tropology.test.parsing :as tp]
            [tropology.parsing :refer :all]
            [tropology.db :as db]
            [tropology.parsing :as p]
            [tropology.base :as b]
            [clojure.string :as s]))


;
; Exception logging
;


(deftest test-log-exception
  (tdb/wipe-test-db)
  (let [conn          (tdb/get-test-connection)
        url           "http://tvtropes.org/pmwiki/pmwiki.php/Anime/CowboyBebop"
        original-data (-> (node-data-from-url url) (assoc :isRedirect true)) ; Create a redirect node
        original-node (tdb/create-node! conn b/base-label original-data)
        t             (Throwable. "Oopsy")
        logged-node   (log-node-exception! conn url t)]
    (println "Yes, we were supposed to get an exception logged up there. ⤴")
    (is (not= logged-node nil))
    (is (get-in original-node [:data :isRedirect]))         ; We did create a redirect node
    (is (nil? (get-in original-node [:data :error])))
    (is (:hasError logged-node))
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
          conn  (tdb/get-test-connection)
          _     (record-page! conn path "http://tvtropes.org/pmwiki/pmwiki.php/Anime/CowboyBebop")
          saved (tdb/get-all-articles)
          rels  (tdb/get-all-article-rels)]
      (is (= 650 (count saved)))
      (is (= 650 (count rels)))
      (doseq [i saved]
        (let [node (:data i)
              values (select-keys node [:outgoing :incoming])]
          (if (= "anime/cowboybebop" (:code node))
            (is (= {:outgoing 650 :incoming 1} values)) ; The page links to itself. Consider removing that.
            (is (= {:outgoing 0 :incoming 1} values))
          ))))
    (prof/profile :trace :Database)
    ))

(deftest test-import-page-set-manga
  ; Test with the Manga page, mostly for profiling reasons, since it'll require
  ; creating 1880 articles, assign 1880 labels and create 1879 relationships.
  (tdb/wipe-test-db)
  (->>
    (let [to-import {:url        (str tp/test-file-path "Manga.html")
                     :provenance "http://tvtropes.org/pmwiki/pmwiki.php/Main/Manga"}
          conn      (tdb/get-test-connection)
          _         (record-page! conn (:url to-import) (:provenance to-import))
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
    (let [
          conn      (tdb/get-test-connection)
          _         (record-page! conn "http://tvtropes.org/pmwiki/pmwiki.php/Main/CircularRedirect")
          all-nodes (tdb/get-all-articles)
          all-rels  (tdb/get-all-article-rels)
          node      (first all-nodes)
          ]
      (println "Yes, we were supposed to get an exception logged up there. ⤴")
      (is (= 1 (count all-nodes)))
      (is (= 0 (count all-rels)))
      (is (= "main/circularredirect" (get-in node [:data :code])))
      (is (get-in node [:data :hasError]))
      )
    (prof/profile :trace :Database)
    )
  )

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
          conn      (tdb/get-test-connection)
          ; We can't pmap these yet, as transactions abort while waiting on a lock
          _         (doall (map #(record-page! conn (:url %) (:provenance %)) to-import))
          all-nodes (tdb/get-all-articles)
          all-rels  (tdb/get-all-article-rels)
          to-review (db/query-for-codes conn ["Main/Manga" "Funny/Film" "Main/HumorDissonance" "Creator/TomokazuSugita"])
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
                                    "creator/tomokazusugita" 1 0
                                    )
      )
    (prof/profile :trace :Database)))

(deftest test-record-page-live
  ; Load a live page. I don't check for how many nodes we're saving since that can
  ; and will change along with the page.
  (tdb/wipe-test-db)
  (let [conn  (tdb/get-test-connection)
        _     (record-page! conn "http://tvtropes.org/pmwiki/pmwiki.php/Main/HomePage")
        saved (tdb/get-all-articles)
        node  (db/query-by-code conn "Main/HomePage")]
    (is (< 0 (count saved)))
    (are [property value] (= value (get-in node [:data property]))
                          :hasError false
                          :category "main"
                          :code "main/homepage"
                          :display "Main/HomePage"
                          :isRedirect false
                          :url "http://tvtropes.org/pmwiki/pmwiki.php/Main/HomePage")
    ))

(deftest test-record-page-same-provenance
  ; See note on test-record-page-local about the provenance URL
  (tdb/wipe-test-db)
  (->>
    (let [path    (str tp/test-file-path "TakeMeInstead-pruned.html")
          conn    (tdb/get-test-connection)
          _       (record-page! conn path "http://tvtropes.org/pmwiki/pmwiki.php/Main/TakeMeInstead")
          saved   (tdb/get-all-articles)
          node    (db/query-by-code conn "Main/TakeMeInstead")
          ignored (db/query-by-code conn "External/LinkOutsideWikiText")
          ]
      (is (= (count saved) 5))
      (is (nil? ignored))
      (are [property value] (= value (get-in node [:data property]))
                            :hasError false
                            :category "main"
                            :code "main/takemeinstead"
                            :display "Main/TakeMeInstead"
                            :isRedirect false
                            :url "http://tvtropes.org/pmwiki/pmwiki.php/Main/TakeMeInstead")
      )
    (prof/profile :trace :Database)
    ))


(deftest test-query-available-after-import
  ; See note on test-record-page-local about the provenance URL
  (tdb/wipe-test-db)
  (->>
    (let [path     (str tp/test-file-path "TakeMeInstead-pruned.html")
          conn     (tdb/get-test-connection)
          _        (record-page! conn path "http://tvtropes.org/pmwiki/pmwiki.php/Main/TakeMeInstead")
          saved    (tdb/get-all-articles)
          to-query (db/query-nodes-to-crawl conn)
          ]
      (is (= 5 (count saved)))
      (is (= 4 (count to-query)))
      (doseq [i to-query]
        (is (p/is-valid-url? i)))
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
          conn     (tdb/get-test-connection)
          _        (record-page! conn path "http://tvtropes.org/pmwiki/pmwiki.php/Main/TakeMeInstead")
          to-query ["Main/TakeMeInstead" "Main/ToBeContinued" "Main/DenserAndWackier"]
          queried  (db/query-for-codes conn to-query)
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
        conn  (tdb/get-test-connection)
        _     (record-page! conn path "http://tvtropes.org/pmwiki/pmwiki.php/Main/TakeMeInstead")
        saved (tdb/get-all-articles)
        _     (record-page! conn path "http://tvtropes.org/pmwiki/pmwiki.php/Main/TakeMeInstead")
        again (tdb/get-all-articles)
        links (cy/tquery conn "MATCH (n:Article {code:'main/takemeinstead'})-[r]->() RETURN r")
        ]
    (is (= (count saved) 5))                                ; There's only five links on the file
    (is (= (count again) 5))                                ; Same number of links is returned the second time
    (is (= (count links) 4))                                ; No duplicated links are created
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
        conn        (tdb/get-test-connection)
        _           (record-page! conn path "http://tvtropes.org/pmwiki/pmwiki.php/Main/TakeMeInsteadRedirector")
        main-node   (db/query-by-code conn "Main/TakeMeInstead")
        redir-node  (db/query-by-code conn "Main/TakeMeInsteadRedirector")
        links-main  (db/query-from conn "Main/TakeMeInstead" :LINKSTO)
        links-redir (db/query-from conn "Main/TakeMeInsteadRedirector" :LINKSTO)]
    (are [property value] (= value (get-in main-node [:data property]))
                          :hasError false
                          :category "main"
                          :code "main/takemeinstead"
                          :display "Main/TakeMeInstead"
                          :isRedirect false
                          :url "http://tvtropes.org/pmwiki/pmwiki.php/Main/TakeMeInstead")
    (are [property value] (= value (get-in redir-node [:data property]))
                          :hasError false
                          ; :category "Main"
                          :code "main/takemeinsteadredirector"
                          :isRedirect true
                          ; Nodes that are initially created as redirects don't get a url, category or display
                          :display nil
                          :category nil
                          :url nil
                          )
    (is (empty? links-redir))
    (is (= 4 (count links-main)))
    ))

