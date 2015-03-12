(ns tropefest.db
  (:require [joda-time :as j]
            [clojurewerkz.neocons.rest :as nr]
            [clojurewerkz.neocons.rest.nodes :as nn]
            [clojurewerkz.neocons.rest.labels :as nl]
            [clojurewerkz.neocons.rest.cypher :as cy]
            [clojurewerkz.neocons.rest.relationships :as nrl]))


(defn get-connection
  "Trivial. Returns a local connection."
  []
  (nr/connect "http://localhost:7474/db/data/"))


;
; Timestamp functions
;

(def update-period (j/days 5))

(defn timestamp-next-update
  "Updates a data hashmap with the current time and the next time for update,
  in milliseconds.
  See http://joda-time.sourceforge.net/apidocs/org/joda/time/Instant.html"
  [data]
  (let [now (j/date-time)]
    (assoc data :timestamp (.getMillis now)
                :nextupdate (.getMillis (j/plus now update-period)))))

(defn timestamp-update
  "Updates a data hashmap with the current time and the next time for update,
  in milliseconds.
  See http://joda-time.sourceforge.net/apidocs/org/joda/time/Instant.html"
  [data]
    (assoc data :timestamp (.getMillis (j/date-time))))

(defn timestamp-create
  "Updates a data hashmap with the current time and the next time for update,
  in milliseconds.
  See http://joda-time.sourceforge.net/apidocs/org/joda/time/Instant.html"
  [data]
  (let [now (j/date-time)]
    (assoc data :timestamp (.getMillis now)
                :nextupdate (.getMillis now))))

;
; Query and creation functions
;

(defn query-by-id
  "Queries for a node id on the properties. Does not filter by label. Notice
  that this is not the same as getting the node directly via its internal id."
  [conn id]
  (let [match (first (cy/tquery conn "MATCH (v {id:{id}}) RETURN v" {:id id}))]
    (if (nil? match)
      nil
      (-> (match "v")
          (select-keys [:data :metadata])))))

(defn create-node
  "Creates a node from a connection with a label"
  [conn label data-items]
  (let [node (nn/create conn (timestamp-create data-items))]
    (do
      (nl/add conn node label)
      node)))

(defn merge-node
  "Updates an existing node, replacing all data items with the ones received,
  and retrieves the existing node."
  [conn ^long id data-items]
  (let [merged (-> (nn/get conn id) (:data) (merge data-items) (timestamp-update))]
    (do
      (nn/update conn id merged)
      (nn/get conn id))))                                   ; Notice that we get it again to retrieve the updated values

(defn create-or-merge-node
  "Creates a node from a connection with a label. If a node with the id
  already exists, label is ignored and the data-items are merged with
  the existing ones.

  Data-items is expected to include the label."
  [conn data-items]
  (let [existing (query-by-id conn (:id data-items))
        id (get-in existing [:metadata :id])]
    (if (empty? existing)
      (create-node conn (:label data-items) data-items)
      (merge-node conn id data-items))))

(defn relate-nodes
  "Links two nodes by a relationship"
  [conn relationship n1 n2]
  (nrl/create conn n1 n2 relationship))

; TODO: Add transaction support