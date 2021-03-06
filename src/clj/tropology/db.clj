(ns tropology.db
  (:refer-clojure :exclude [update])
  (:require [joda-time :as j]
            [clojure.string :refer [lower-case]]
            [korma.core :refer :all]
            [korma.db :as kdb]
            [tropology.base :as b]
            [tropology.s3 :as s3]
            [taoensso.timbre.profiling :as prof]
            [environ.core :refer [env]]
            [numergent.utils :as u]))


;
; Database definition
;

(defn- key-rename [n]
  (clojure.string/replace n "-" "_"))

(defn- sql-field-rename [n]
  (-> n
      name
      (clojure.string/replace "_" "-")
      keyword))

(defn rename-db-keywords [row]
  (->> (keys row)
       (map #(hash-map % (sql-field-rename %)))
       (into {})
       (clojure.set/rename-keys row)))


(kdb/defdb test-db (kdb/postgres {:host     (:db-host env)
                                  :db       (:db-name env)
                                  :user     (:db-user env)
                                  :password (:db-password env)
                                  :naming   {:keys   key-rename
                                             :fields key-rename}
                                  }))

(defentity pages)
(defentity links)
(defentity contents)


(defn save-page!
  "Saves a page record, or updates an existing one.

  Likely could be re-writen to use create-if-unknown! passing an else
  function to be executed in the case that the record exists."
  [record]
  (->>
    (let [code    (:code record)
          current (select pages (where {:code code}))]
      (if (empty? current)
        (insert pages (values record))
        (update pages (set-fields record) (where {:code code})))
      )
    (prof/p :save-page)))

;
; Timestamp functions
;

(def expiration-period (j/days (Integer. (:expiration env))))

(defn timestamp-next-update
  "Updates a data hashmap with the current time and the next time for update,
  in milliseconds.
  See http://joda-time.sourceforge.net/apidocs/org/joda/time/Instant.html"
  [data]
  (let [now (j/date-time)]
    (assoc data :time-stamp (.getMillis now)
                :next-update (.getMillis (j/plus now expiration-period)))))

(defn timestamp-update
  "Updates a data hashmap with the current time and the next time for update,
  in milliseconds.
  See http://joda-time.sourceforge.net/apidocs/org/joda/time/Instant.html"
  [data]
  (assoc data :time-stamp (.getMillis (j/date-time))))

(defn timestamp-create
  "Adds to a data hashmap the current time and the next time for update,
  in milliseconds.  If the hashmap already contains either of these values,
  they are preseved.
  See http://joda-time.sourceforge.net/apidocs/org/joda/time/Instant.html"
  [data]
  (let [now (.getMillis (j/date-time))]
    (merge {:time-stamp now :next-update now} data)))



;
; Page import
;


(defn create-all!
  "Creates a list of pages"
  [records]
  (->>
    (if (not-empty records)
      (insert pages (values records)))                      ; Doesn't return all records created, just the last one
    (prof/p :create-all)))


(defn fetch-random-contents-code []
  (->> (select pages
               (fields :code)
               (where (> :size 0))
               (order (raw "random()"))
               (limit 1))
       (map rename-db-keywords)
       first
       :code
       (prof/p :fetch-random-code)))

(defn query-for-codes
  "Returns the pages for the list of codes provided"
  [codes]
  (->> (select pages
               (where {:code [in (map lower-case codes)]}))
       (map rename-db-keywords)
       (prof/p :query-for-codes)
       ))


(defn create-all-unknown! [records]
  (->> (let [codes     (map :code records)
             existing  (->> (query-for-codes codes) (map :code))
             remaining (remove #(u/in-seq? existing (:code %)) records)
             ]
         (create-all! remaining))
       (prof/p :create-all-unknown))
  )

(defn create-relationships! [code links-to rel]
  (when (not-empty links-to)
    (prof/p
      :delete-links
      (delete links (where {:from-code code :type (name rel)})))
    (prof/p
      :create-links
      (insert links
              (values (pmap #(hash-map :from-code code :to-code % :type (name rel)) links-to)))))
  )


(defn log-error!
  "Logs an error for a node and returns the updated node"
  [node]
  (let [code (lower-case (:code node))
        data (-> (merge {:display code :category (b/category-from-code code) :image nil :has-error false :is-redirect false} node) ; Add defaults
                 (merge {:code code})
                 timestamp-update)
        _    (save-page! data)]
    (->> (query-for-codes [code])
         first)))


(defn save-page-contents!
  "Saves a page contents record, or updates an existing one."
  [{:keys [code html]}]
  (prof/p :save-contents
          (update pages
                  (where {:code (lower-case code)})
                  (set-fields {:size (count html)}))
          (s3/put-string! (lower-case code) html)
          )
  )

(defn create-page-and-links!
  "Creates a page and all its related to links in a single transaction.
  Returns a future to the action where we're saving their contents

  I considered doing a try-times here so that we can parallelize and retry
  in transient exceptions. It doesn't make any sense to retry and walk into
  the same deadlock right away, and pmap doesn't create a thread for each
  item... so putting a thread to sleep when one fails actually makes the
  whole process slower on my tests.

  http://stackoverflow.com/questions/1879885/clojure-how-to-to-recur-upon-exception
  http://stackoverflow.com/questions/5021788/how-many-threads-does-clojures-pmap-function-spawn-for-url-fetching-operations
  "
  ([node]
   (create-page-and-links! node nil nil {:is-redirect false}))
  ([node rel links {:keys [is-redirect redirector]}]
   (let [code       (lower-case (:code node))
         link-codes (map :code links)
         html       (:html node)
         upload     (future (save-page-contents! {:code code :html html}))
         all-codes  (conj link-codes (:code node))]

     (kdb/transaction
       (save-page! (-> node
                       timestamp-next-update
                       (dissoc :html)
                       (assoc :size (count html))))
       (create-all-unknown! links)
       (create-relationships! code link-codes rel)
       (update pages
               (where {:code [in all-codes]})
               (set-fields {:outgoing (raw "(SELECT COUNT(*) FROM links WHERE from_code = code)")
                            :incoming (raw "(SELECT COUNT(*) FROM links WHERE to_code = code)")
                            }))
       ; Save the redirector last, since it needs to reference the previous record
       (if is-redirect
         (-> redirector
             timestamp-update
             (assoc :is-redirect true :redirects-to code)
             save-page!)))
       upload
     )
    ))


;
; Node query and creation functions
;


(defn query-by-code
  "Queries for a node id on the properties. Does not filter by label. Notice
  that this is not the same as getting the node directly via its internal id."
  [code]
  (->>
    (when (not-empty code)
      (first (query-for-codes [code])))
    (prof/p :query-by-code)))

(defn query-nodes-to-crawl
  "Return the nodes that need to be crawled according to their nextupdate timestamp"
  ([]
   (query-nodes-to-crawl 100))
  ([node-limit]
   (query-nodes-to-crawl node-limit (.getMillis (j/date-time))))
  ([node-limit time-limit]
   (if (pos? node-limit)                                    ; If we pass a limit of 0, applying ORDER BY will raise an exception
     (->> (select pages
                  (fields :url)
                  (where {:is-redirect false :has-error false :next-update [< time-limit]})
                  (order :next-update :ASC)
                  (limit node-limit))
          (pmap :url))
     '())))

;
; Link querying
;

(defn- query-node-rel
  [^String code rel from to]
  (->> (select pages
               (fields :code :url :title :display :url :category :incoming :outgoing)
               (join links (= to :code))
               (where {from (lower-case code) :links.type (name rel)})
               )
       (map rename-db-keywords))
  )

(defn query-from
  "Retrieves the list of nodes that a node links to (emanating from)

  We could probably write it getting the relationships and walking through
  them, but going with cypher for now to test."
  [^String code rel]
  (->> (query-node-rel code rel :links.from-code :links.to-code)
       (prof/p :query-from)))


(defn query-to
  "Retrieves the list of nodes that links to a node.
  Yes, the parameter order is the opposite from query-links-from,
  since I think it better indicates the relationship."
  [rel ^String code]
  (->> (query-node-rel code rel :links.to-code :links.from-code)
       (prof/p :query-to)))


(defn query-rel-list
  "Returns the list of relationship pairs that are either to or from pages
  where we get the code on the list"
  ([code-list]
   (query-rel-list code-list :LINKSTO))
  ([code-list rel]
   (->>
     (let [query (-> (select* links)
                     (fields :from-code :to-code)
                     (where {:type      (name rel)
                             :to-code   [in code-list]
                             :from-code [in code-list]})
                     (modifier "distinct")
                     )
           ]
       ; (println (as-sql query))
       (->> query
            select
            (map rename-db-keywords)
            ))
     (prof/p :query-rel-list)
     ))
  )
