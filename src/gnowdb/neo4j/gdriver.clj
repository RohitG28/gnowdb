(ns gnowdb.neo4j.gdriver
  (:gen-class)
  (:require [clojure.set :as clojure.set]
            [clojure.java.io :as io]
            [clojure.string :as clojure.string]
            [gnowdb.neo4j.grcs_locks :as grcs_locks :only [queueUUIDs]])
  (:use [gnowdb.neo4j.gqb]))

(import '[org.neo4j.driver.v1 Driver AuthTokens GraphDatabase Record Session StatementResult Transaction Values])

(declare getNBH)

(defn getNeo4jDBDetails
  [details]
  (if (bound? (resolve `driver))
    (.close (var-get (resolve `driver)))
    )
  (def ^{:private true} driver 
    (GraphDatabase/driver (details :bolt-url) (AuthTokens/basic (details :username) (details :password)))
    )
  )

(defn getRCSEnabled
  [details]
  (def ^{:private true} rcsEnabled? (details :rcsEnabled)))

(defn- createSummaryMap
  "Creates a summary map from StatementResult object.
	This Object is returned by the run() method of session object
	To be used for cypher queries that dont return nodes
	Driver should not be closed before invoking this function"
  [statementResult]
  (let [summaryCounters (.counters (.consume statementResult))]
    {:constraintsAdded (.constraintsAdded summaryCounters) :constraintsRemoved (.constraintsRemoved summaryCounters) :containsUpdates (.containsUpdates summaryCounters) :indexesAdded (.indexesAdded summaryCounters) :indexesRemoved (.indexesRemoved summaryCounters) :labelsAdded (.labelsAdded summaryCounters) :labelsRemoved (.labelsRemoved summaryCounters) :nodesCreated (.nodesCreated summaryCounters) :nodesDeleted (.nodesDeleted summaryCounters) :propertiesSet (.propertiesSet summaryCounters) :relationshipsCreated (.relationshipsCreated summaryCounters) :relationshipsDeleted (.relationshipsDeleted summaryCounters)}))

(defn- createSummaryString 
  "Creates Summary String only with only necessary information.
  	Takes summaryMap created by createSummaryMap function."
  [summaryMap]
  (reduce
   (fn [string k]
     (if(not= (k 1) 0)
       (str string (str (clojure.string/capitalize (subs (str (k 0)) 1 2)) (subs (str (k 0)) 2)) " :" (k 1) " ;")
       string
       )
     )
   ""
   summaryMap
   )
  )

(defn- getFullSummary
  "Returns summaryMap and summaryString"
  [statementResult]
  (let [sumMap (createSummaryMap statementResult)]
    {:summaryMap sumMap :summaryString (createSummaryString sumMap)}
    )
  )

(defn- getCombinedFullSummary
  "Combine FullSummaries obtained from 'getFullSummary'"
  [fullSummaryVec]
  (let [ consolidatedMap 
        (apply merge-with
               (fn [& args]
                 (if (some #(= (type %) java.lang.Boolean) args)
                   (if (some identity args) true false)
                   (apply + args)
                   )
                 )
               {:constraintsAdded 0 :constraintsRemoved 0 :containsUpdates false :indexesAdded 0 :indexesRemoved 0 :labelsAdded 0 :labelsRemoved 0 :nodesCreated 0 :nodesDeleted 0 :propertiesSet 0 :relationshipsCreated 0 :relationshipsDeleted 0}	
               (map #(% :summaryMap) fullSummaryVec)
               )
        ]
    {:summaryMap consolidatedMap
     :summaryString (createSummaryString consolidatedMap)
     }
    )
  )

(defn- parse
  [data]
  (cond ;More parsers can be added here. (instance? /*InterfaceName*/ data) <Return Value>
    (instance? org.neo4j.driver.v1.types.Node data) {:labels (into [] (.labels data)) :properties (into {} (.asMap data))}
    (instance? org.neo4j.driver.v1.types.Relationship data) {:labels (.type data) :properties (into {} (.asMap data)) :fromNode (.startNodeId data) :toNode (.endNodeId data)}
    (instance? org.neo4j.driver.v1.types.Path data) {:start (parse (.start data)):end (parse (.end data)) :segments (map (fn [segment] {:start (parse (.start segment)) :end (parse (.end segment)) :relationship (parse (.relationship segment))}) data) :length (reduce (fn [counter, data] (+ counter 1)) 0 data)}
    :else data
    )
  )

(defn getRCSUUIDListMap
  "Create a map with keys :count,:RCSUUIDList, using `finalResult` of a transaction from gdriver/runQuery.
  :RCSUUIDList will be a vector of maps with keys :UUIDList, :labels.
  :queriesList should be the query maps that are passed to gdriver/runQuery"
  [& {:keys [:finalResult
             :queriesList]
      :or {:finalResult {:results []}
           :queriesList []}}]
  {:pre [(= (count (finalResult :results))
            (count queriesList))]}
  (reduce
   (fn [RCSUUIDListMap res]
     (let [count (RCSUUIDListMap :count)
           RCSUUIDList (RCSUUIDListMap :RCSUUIDList)
           qL ((vec queriesList) count)
           rcs-vars (qL :rcs-vars)
           labels (qL :labels)
           doRCS? (qL :doRCS?)]
       (if doRCS?
         (assoc RCSUUIDListMap
                :RCSUUIDList (conj RCSUUIDList
                                   (reduce (fn [UUIDListMap r]
                                             (assoc UUIDListMap
                                                    :UUIDList (pmap #(r %) ;; pmap is needed here, as if 1000 nodes are changed, pmap will be faster. pmap might slow things down if there are a low number of nodes affected. TODO : get optimized threshold to make the decision between pmap and map
                                                                    rcs-vars)))
                                           {:UUIDList []
                                            :labels labels
                                            :doRCS? doRCS?} res))
                :count (inc count))
         RCSUUIDListMap
         )))
   {:count 0
    :RCSUUIDList []}
   (finalResult :results)))

(defn reduceRCSUUIDListMap
  "Groups UUIDs based on labels.
  if a UUIDList's labels are a superset of another UUIDList, it would be 'unioned' to the latter
  :RCSUUIDListMap should be output of gdriver/getRCSUUIDListMap"
  [& {:keys [:RCSUUIDListMap]
      :or {:RCSUUIDListMap {:count 0
                            :RCSUUIDList []}}}]
  (let [RCSUUIDList (sort #(> (%1 :UUIDCount)
                              (%2 :UUIDCount))
                          (pmap (fn [UL]
                                  (assoc UL
                                         :UUIDList (into #{} (ddistinct (UL :UUIDList)))
                                         :labels (into #{} (ddistinct (UL :labels)))
                                         :UUIDCount (count (UL :UUIDList))))
                                (RCSUUIDListMap :RCSUUIDList)))
        reducedRCSUUIDList (reduce (fn
                                     [RUL UL]
                                     (let [candidateULs (reduce (fn [CULs cul]
                                                                  (if (and (>= (cul :UUIDCount)
                                                                               (UL :UUIDCount))
                                                                           (clojure.set/subset? (cul :labels)
                                                                                                (UL :labels)))
                                                                    (conj CULs {:index (.indexOf RUL cul)
                                                                                :UUIDCount (cul :UUIDCount)})
                                                                    CULs))
                                                                []
                                                                RUL)
                                           largestCUL (if (empty? candidateULs)
                                                        (apply max-key [:UUIDCount nil])
                                                        (apply max-key :UUIDCount candidateULs))]
                                       (if (nil? largestCUL)
                                         (conj RUL UL)
                                         (let [ri (largestCUL :index)
                                               lu (RUL ri)
                                               union (clojure.set/union (UL :UUIDList)
                                                                        (lu :UUIDList))]
                                           (assoc RUL
                                                  ri (assoc lu
                                                            :UUIDList union
                                                            :UUIDCount (count union)))))))
                                   []
                                   RCSUUIDList)]
    (assoc RCSUUIDListMap :RCSUUIDList reducedRCSUUIDList)))

(defn- doRCS
  [& {:keys [:finalResult
             :queriesList]}]
  (let [RCSUUIDListMap (reduceRCSUUIDListMap :RCSUUIDListMap (getRCSUUIDListMap :finalResult finalResult
                                                                                :queriesList queriesList))]
    
    (doall (pmap (fn [umap]
                  (if (umap :doRCS?)
                    (grcs_locks/queueUUIDs :UUIDList (umap :UUIDList)
                                           :nbhs (getNBH :labels (umap :labels)
                                                         :UUIDList (umap :UUIDList))
                                           :labels (umap :labels))))
                (RCSUUIDListMap :RCSUUIDList)))
    ))

(defn runQuery
  "Takes a list of queries and executes them. Returns a map with all records and summary of operations iff all operations are successful otherwise fails.
	Input Format: {:query <query> :parameters <Map of parameters as string key-value pairs>} ...
	Output Format: {:results [(result 1) (result 2) .....] :summary <Summary Map>}
	In case of failure, {:results [] :summary <default full summary>}"
  [& queriesList]
  (let [
        session (.session driver)
        transaction (.beginTransaction session)
        ]
    (try
      (let
          [finalResult (reduce
                        (fn [resultMap queryMap]
                          (let [statementResult (.run transaction (queryMap :query) (java.util.HashMap. (queryMap :parameters)))]
                            {:results (conj 
                                       (resultMap :results) 
                                       (map 
                                        (fn [record]
                                          (into {} 
                                                (map 
                                                 (fn 
                                                   [attribute]
                                                   {(attribute 0) (parse (attribute 1))}
                                                   )
                                                 (into {} (.asMap record))
                                                 )
                                                )
                                          ) 
                                        (.list statementResult)
                                        )) 
                             :summary (getCombinedFullSummary [(resultMap :summary) (getFullSummary statementResult)])
                             }
                            )
                          )
                        {:results [] :summary (getCombinedFullSummary [])}
                        queriesList
                        )]
        (if
            (and (((finalResult :summary) :summaryMap) :containsUpdates)
                 rcsEnabled?)
          (doRCS :finalResult finalResult
                 :queriesList queriesList))
        (.success transaction)
        finalResult
        )
      (catch Throwable e (.failure transaction) {:results [] :summary {:summaryMap {} :summaryString (.toString e)}})
      (finally (.close transaction) (.close session))
      )
  )
)

(defn runTransactions
  "Takes lists of arguments to run in separate transactions"
  [& transactionList]
  (let
      [result (map
               #(apply runQuery %)
               transactionList
               )]
    {:results result
     :summary (getCombinedFullSummary (map #(% :summary) result))
     }
    )
  )

(defn getNodesByUUID
  "Get Nodes by UUID"
  [& {:keys [:labels
             :UUIDList]
      :or {:labels []}}]
  {:pre [(coll? labels)
         (coll? UUIDList)
         (every? string? UUIDList)]}
  (let [builtQuery {:query (str "MATCH (node"(createLabelString :labels labels)") WHERE node.UUID in {UUIDList} return node")
                    :parameters {"UUIDList" UUIDList}}]
    (reduce #(merge %1 {((%2 :properties) "UUID") %2}) {} (map #(% "node") (first ((runQuery builtQuery) :results))))
    )
  )

(defn getInRels
  [& {:keys [:labels
             :UUIDList]
      :or {:labels []
           :UUIDList []}}]
  {:pre [(every? string? UUIDList)]}
  (let [labelString (createLabelString :labels labels)
        builtQuery {:query (str "MATCH (n"
                                labelString
                                " )<-[relation]-(node)"
                                " WHERE n.UUID IN {UUIDList}"
                                " RETURN relation, node.UUID as fromUUID, n.UUID as toUUID")
                    :parameters {"UUIDList" UUIDList}}
        inRels (first ((runQuery builtQuery) :results))]
    (reduce #(assoc %1 %2
                    (into #{} (filter
                               (fn [rel]
                                 (= %2 (rel "toUUID"))) (map (fn [rel]
                                                               (assoc rel "relation"(dissoc (rel "relation") :fromNode :toNode))) inRels)))) {} UUIDList)))

(def nbhAtom (atom nil)) 

(defn getNBH
  "GET NBH"
  [& {:keys [:labels
             :UUIDList]
      :or {:labels []
           :UUIDList []}}]
  {:pre [(coll? UUIDList)
         (every? string? UUIDList)]}
  (reset! nbhAtom
          (let [nodesMatched (getNodesByUUID :UUIDList UUIDList)
                nodeNBHs (getInRels :labels labels
                                    :UUIDList UUIDList)
                ]
            (reduce #(merge %1 {(%2 0) {:node (assoc (%2 1) :labels (into #{} ((%2 1) :labels)))
                                        :inRelations (nodeNBHs (%2 0))}})
                    {} nodesMatched))))
