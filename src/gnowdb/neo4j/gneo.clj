(ns gnowdb.neo4j.gneo
  (:gen-class)
  (:require [clojure.set :as clojure.set]
            [clojure.java.io :as io]
            [clojure.string :as clojure.string]
            [gnowdb.neo4j.gdriver :as gdriver]))

(import '[org.neo4j.driver.v1 Driver AuthTokens GraphDatabase Record Session StatementResult Transaction Values]
        '[java.io PushbackReader])

(defn addStringToMapKeys
  [stringMap string]
  (apply conj
         (map
          (fn
            [[stringKey value]]
            {(str stringKey string) value}
            )
          stringMap
          )
         )
  )

(defn removeVectorStringSuffixes
  "Removes the string suffix from the Vector members"
  [mapKeyVector stringSuffix]
  (
   into []
   (
    map (fn
          [keyValue]
          (if (clojure.string/ends-with? (str keyValue) stringSuffix) 
            (subs (str keyValue) 0 (clojure.string/last-index-of (str keyValue) stringSuffix))
            (str keyValue)
            )
          )
    mapKeyVector
    )
   )
  )

(defn createParameterPropertyString
	"Create Property String with parameter fields using map keys"
	[propertyMap & [characteristicString]]
	;;The characteristicString is sometimes appended to map keys to distinguish
	;;the keys when multiple maps and their keys are used in the same cypher
	;;query with parameters
	(str "{ "
		(clojure.string/join ", " 
			(vec 
				(map #(str %1 ":{" %2 "}")
					(removeVectorStringSuffixes (vec (keys propertyMap)) characteristicString)
					(vec (keys propertyMap))
				)
			)
		)
		" }"
	)
)


(defn getAllLabels
  "Get all the Labels from the graph, parsed."
  []
  (((gdriver/runQuery {:query "MATCH (n) RETURN DISTINCT LABELS(n)" :parameters {}}) :results) 0)
  )

(defn getAllNodes
  "Returns a lazy sequence of labels and properties of all nodes in the graph"
  []
  (map #(% "n") (((gdriver/runQuery {:query "MATCH (n) RETURN n" :parameters {}}) :results) 0))
  )

(defn combinePropertyMap
  "Combine PropertyMaps and associated propertyStrings.
	Name PropertyMaps appropriately.
	Input PropertyMaps as map of maps.
	Keys Should be strings"
  [propertyMaps]
  (let [propertyStringMap (atom {}) combinedPropertyMap (atom {})]
    (doall (map (fn
                  [mapKey]
                  (let [newPropertyMap (addStringToMapKeys (propertyMaps mapKey) mapKey)]
                    (swap! combinedPropertyMap merge newPropertyMap)
                    (swap! propertyStringMap assoc mapKey (createParameterPropertyString newPropertyMap mapKey)))) (vec (keys propertyMaps))))
    {:combinedPropertyMap @combinedPropertyMap :propertyStringMap @propertyStringMap}))

(defn createNewNode
  "Create a new node in the graph. Without any relationships.
	Node properties should be a clojure map.
	Map keys will be used as neo4j node keys.
	Map keys should be Strings only.
	Map values must be neo4j compatible Objects"
  [& {:keys [label parameters execute?] :or {execute? true parameters {}}}]
  (if execute?
    ((gdriver/runQuery {:query (str "CREATE (node:" label " " (createParameterPropertyString parameters) " )") :parameters parameters}) :summary)
    {:query (str "CREATE (node:" label " " (createParameterPropertyString parameters) " )") :parameters parameters}
    )
  )

(defn createRelation
  "Relate two nodes matched with their properties (input as clojure map) with it's own properties"
  [& {:keys [inNodeLabel inNodeParameters relationshipType relationshipParameters outNodeLabel outNodeParameters execute?] :or {execute? true}}]
  (let [combinedProperties (combinePropertyMap {"1" inNodeParameters "2" outNodeParameters "R" relationshipParameters})]
    (if execute?
      ((gdriver/runQuery {:query (str "MATCH (node1:" inNodeLabel " " ((combinedProperties :propertyStringMap) "1") " ) , (node2:" outNodeLabel " " ((combinedProperties :propertyStringMap) "2") " ) CREATE (node1)-[:" relationshipType " " ((combinedProperties :propertyStringMap) "R") " ]->(node2)") :parameters (combinedProperties :combinedPropertyMap)}) :summary)
      {:query (str "MATCH (node1:" inNodeLabel " " ((combinedProperties :propertyStringMap) "1") " ) , (node2:" outNodeLabel " " ((combinedProperties :propertyStringMap) "2") " ) CREATE (node1)-[:" relationshipType " " ((combinedProperties :propertyStringMap) "R") " ]->(node2)") :parameters (combinedProperties :combinedPropertyMap)}
      )
    )
  )

(defn deleteDetachNodes
  "Delete node(s) matched using property map and detach (remove relationships)"
  [& {:keys [label parameters execute?] :or {execute? true parameters {}}}]
  (if execute?
    ((gdriver/runQuery {:query (str "MATCH (node:" label " " (createParameterPropertyString parameters) " ) DETACH DELETE node") :parameters parameters}) :summary)
    {:query (str "MATCH (node:" label " " (createParameterPropertyString parameters) " ) DETACH DELETE node") :parameters parameters}
    )
  )

(defn deleteNodes
  "Delete node(s) matched using property map"
  [& {:keys [label parameters execute?] :or {execute? true parameters {}}}]
  (if execute?
    ((gdriver/runQuery {:query (str "MATCH (node:" label " " (createParameterPropertyString parameters) " ) DELETE node") :parameters parameters}) :summary)
    {:query (str "MATCH (node:" label " " (createParameterPropertyString parameters) " ) DELETE node") :parameters parameters}
    )
  )

(defn createNodeEditString
	"Creates a node edit string.
	eg.., nodeName.prop1=val1 , nodeName.prop2=val2"
	[nodeName editPropertyMap & [characteristicString]]
	(str " SET  "
		(clojure.string/join " , "
			(
				vec(map #(str nodeName"."%1" = {"%2"}")
						(if characteristicString 
							(removeVectorStringSuffixes (vec (keys editPropertyMap)) characteristicString)	
							(vec (keys editPropertyMap))
						)
						(vec (keys editPropertyMap))
					)
			)
		)
		"  "
	)
)

(defn editNodeProperties
  "Edit Properties of Node(s)"
  [& {:keys [label parameters changeMap execute?] :or {execute? true parameters {}}}]
  (let [mPM (addStringToMapKeys parameters "M") tPME (addStringToMapKeys changeMap "E")]
    (if execute?
      ((gdriver/runQuery {:query (str "MATCH (node1:" label " " (createParameterPropertyString mPM "M") " ) " (createNodeEditString "node1" tPME "E")) :parameters (merge mPM tPME)}) :summary)
      {:query (str "MATCH (node1:" label " " (createParameterPropertyString mPM "M") " ) " (createNodeEditString "node1" tPME "E")) :parameters (merge mPM tPME)}
      )
    )
  )

(defn createNodeRemString
	"Creates a node property removal string.
	eg.REMOVE nodeName nodeName.prop1 , nodeName.prop2"
	[nodeName remPropertyVec]
	(str "REMOVE "
		(clojure.string/join ", "
			(	
				vec (map #(str nodeName"."%1) 
						remPropertyVec
					)	
			)
		)
	)	
)

(defn removeNodeProperties
  "Remove properties from Node"
  [& {:keys [label parameters removeProperties execute?] :or {execute? true parameters {}}}]
  (if execute?
    ((gdriver/runQuery {:query (str "MATCH (node1:" label " " (createParameterPropertyString parameters) " ) " (createNodeRemString "node1" removeProperties)) :parameters parameters}) :summary)
    {:query (str "MATCH (node1:" label " " (createParameterPropertyString parameters) " ) " (createNodeRemString "node1" removeProperties)) :parameters parameters}
    )
  )

(defn getNodes
  "Get Node(s) matched by label and propertyMap"
  [& {:keys [label parameters execute?] :or {execute? true parameters {}}}]
  (if execute?
    (map #(% "node") (((gdriver/runQuery {:query (str "MATCH (node:" label " " (createParameterPropertyString parameters) ") RETURN node") :parameters parameters}) :results) 0))
    {:query (str "MATCH (node:" label " " (createParameterPropertyString parameters) ") RETURN node") :parameters parameters}
    )
  )

;;Class building functions start here

(defn prepMapAsArg
  "Converts a map so that it can be used as keyArgs"
  [keyMap]
  (reduce #(concat %1 %2) keyMap))

(defn reduceQueryColl
  "Reduce collections/sub-collections of queries (({:query '...' :parameters {}})...()...()...) to a single collection of queries ({:query '...' :parameters {}} {...} {...})"
  [queryCollection]
  (reduce
   #(if
        (some map? %2)
      (concat %1 %2)
      (reduceQueryColl %1)
      )
   []
   queryCollection
   )
  )

(defn manageConstraints
  "Manage unique constraints or existance constraints.
  :label should be a string.
  :CD should be either of 'CREATE','DROP'
  :propertyVec should be a vector of properties(string).
  :constraintType should be either UNIQUE or NODEEXISTANCE or RELATIONEXISTANCE or NODEKEY.
  if :constraintType is NODEKEY, :propertyVec should be a vector of vectors of properties(string).
  :execute? (boolean) whether the constraints are to be created, or just return preparedQueries"
  [& {:keys [:label :CD :propertyVec :constraintType :execute?]}]
  {:pre [
         (string? label)
         (contains? #{"CREATE" "DROP"} CD)
         (not (empty? propertyVec))
         (contains? #{"UNIQUE" "NODEEXISTANCE" "RELATIONEXISTANCE" "NODEKEY"} constraintType)
         (if
             (= "NODEKEY" constraintType)
           (every? #(and (coll? %) (not (empty? %))) propertyVec)
           true
           )
         ]
   }
  (let [queryBuilder (case constraintType
                       "UNIQUE" #(str "(label:" label ") ASSERT label." % " IS UNIQUE")
                       "NODEEXISTANCE" #(str "(label:" label ") ASSERT exists(label." % ")")
                       "RELATIONEXISTANCE" #(str "()-[label:" label "]-() AS ASSERT exists(label." % ")")
                       "NODEKEY" #(str
                                   "(label:" label ") ASSERT (" (clojure.string/join
                                                                 ", "
                                                                 (map (fn [property] (str "label." property)) %)
                                                                 )
                                   ") IS NODE KEY"
                                   )
                       )
        builtQueries (map #(->
                            {:query
                             (str CD " CONSTRAINT ON " (queryBuilder %))
                             :parameters {}
                             }
                            ) propertyVec)
        ]
    (if
        execute?
      (apply gdriver/runQuery builtQueries)
      builtQueries)
    )
  )

(defn manageUniqueConstraints
  "Create/Drop Unique Constraints on label properties.
  :label is treated as a Node Label.
  :CD can be CREATE,DROP.
  :propertyVec should be a vector of properties"
  [& {:keys [:label :CD :propertyVec :execute?] :as keyArgs}]
  (apply manageConstraints
         (prepMapAsArg
          (assoc
           keyArgs
           :constraintType "UNIQUE"
           )
          )
         )
  )

(defn manageExistanceConstraints
  "Create/Drop Existance Constraints on label properties.
  :label is treated as a Node label or relation label based on value of NR.
  :CD can be CREATE, DROP.
  :propertyVec should be a vector of properties.
  :NR should be either NODE or RELATION"
  [& {:keys [:label :CD :propertyVec :NR :execute?] :as keyArgs}]
  {:pre [(contains? #{"CREATE" "DROP"} NR)]
   }
  (apply manageConstraints
         (prepMapAsArg
          (assoc
           (dissoc
            keyArgs :NR)
           :constraintType (str NR "EXISTANCE")
           )
          )
         )
  )

(defn manageNodeKeyConstraints
  "Create/Drop Node Key Constraints on node label properties.
  :label is treated as node label.
  :CD can be CREATE, DROP.
  :propPropVec should be a vector of vectors of properties(string)."
  [& {:keys [:label :CD :propPropVec :execute?] :as keyArgs}]
  ;;For some reason, creating/dropping a nodekey doesn't reflect on summary.
  ;;So don't be surprised if no errors occur or no changes exist in the summary.
  (apply manageConstraints
         (prepMapAsArg
          (assoc
           keyArgs
           :constraintType "NODEKEY")
          )
         )
  )

(defn createNCConstraints
  "Create Constraints that apply to nodes with label NeoConstraint"
  []
  (manageNodeKeyConstraints :label "NeoConstraint" :CD "CREATE" :propPropVec [["constraintType" "constraintTarget"]] :execute? true))

(defn createCATConstraints
  "Create Constraints that apply to relationships with label NeoConstraintAppliesTo"
  []
  (manageExistanceConstraints :label "NeoConstraintAppliesTo" :CD "CREATE" :propertyVec ["constraintValue"] :NR "RELATION" :execute? true))

(defn createNeoConstraint
  "Creates a NeoConstraint Node that describes a supported neo4j constraint.
  :constraintType should be either of UNIQUE,EXISTANCE,NODEKEY.
  :constraintTarget should be either of NODE,RELATION.
  If :constraintTarget is RELATION, then constraintType can only be EXISTANCE"
  [& {:keys [:constraintType :constraintTarget :execute?] :as keyArgs}]
  {:pre [
         (contains? #{"UNIQUE" "EXISTANCE" "NODEKEY"} constraintType)
         (contains? #{"NODE" "RELATION"} constraintTarget)
         (and
          (contains? #{"UNIQUE" "NODEKEY"} constraintType)
          (= "RELATION" constraintTarget)
          )
         ]
   }
  (createNewNode "NeoConstraint" {"constraintType" constraintType "constraintTarget" constraintTarget})
  )

(defn createAllNeoConstraints
  "Creates all NeoConstraints"
  []
  (pcalls
   #(createNeoConstraint :constraintType "UNIQUE" :constraintTarget "NODE")
   #(createNeoConstraint :constraintType "NODEKEY" :constraintTarget "NODE")
   #(createNeoConstraint :constraintType "EXISTANCE" :constraintTarget "NODE")
   #(createNeoConstraint :constraintType "EXISTANCE" :constraintTarget "RELATION")
   )
  )

;;TODO CUSTOM CONSTRAINTS

(defn createATConstraints
  "Creates Constraints that apply to nodes with label AttributeType"
  []
  (manageNodeKeyConstraints :label "AttributeType" :CD "CREATE" :propPropVec [["_name" "_datatype"]] :execute? true))

(defn createAttributeType
  "Creates a node with Label AttributeType.
  :_name should be a string
  :_datatype should be a string of one of the following: 'java.lang.Boolean', 'java.lang.Byte', 'java.lang.Short', 'java.lang.Integer', 'java.lang.Long', 'java.lang.Float', 'java.lang.Double', 'java.lang.Character', 'java.lang.String', 'java.util.ArrayList'"
  [& {:keys [:_name :_datatype] :as keyArgs}]
  {:pre [
         (string? _name)
         (contains? #{"java.lang.Boolean",
                      "java.lang.Byte",
                      "java.lang.Short",
                      "java.lang.Integer",
                      "java.lang.Long",
                      "java.lang.Float",
                      "java.lang.Double",
                      "java.lang.Character",
                      "java.lang.String",
                      "java.util.ArrayList"}
                    _datatype)
         ]
   }
  (createNewNode "AttributeType" {"_name" _name "_datatype" _datatype}))

(defn createClassConstraints
  "Create Constraints that apply to nodes with label Class"
  []
  (manageExistanceConstraints :label "Class" :CD "CREATE" :propertyVec ["isAbstract" "classType"] :NR "NODE" :execute? true)
  (manageNodeKeyConstraints :label "Class" :CD "CREATE" :propPropVec [["className"]] :execute? true))

(defn getClassAttributeTypes
  "Get all AttributeTypes 'attributed' to a class"
  [className]
  (gdriver/runQuery
   {:query "MATCH (class:Class {className:{className}})-[rel:HasAttributeType]-(att:AttributeType) RETURN att"
    :parameters {"className" className}
    }
   )
  )

(defn getClassNeoConstraints
  "Get all NeoConstraints attributed to a class"
  [className]
  (gdriver/runQuery
   {:query "MATCH (class:Class {className:{className}})<-[ncat:NeoConstraintAppliesTo]-(neo:NeoConstraint) RETURN ncat,neo"
    :parameters {"className" className}
    }
   )
  )

(defn applyClassNeoConstraint
  "Apply a NeoConstraint that apply to a class.
  :className string
  :constraintType UNIQUE,NODEKEY,EXISTANCE
  :constraintTarget NODE,RELATION
  :constraintValue depends upon :_constraintTarget and :_constraintType
  :execute?"
  [& {:keys [:className :constraintType :constraintTarget :constraintValue :execute?] :as keyArgs}]
  {:pre [
         (contains? #{"UNIQUE" "NODEKEY" "EXISTANCE"} constraintType)
         (contains? #{"NODE" "RELATION"} constraintTarget)]}
  (manageConstraints :label className
                     :CD "CREATE"
                     :propertyVec [(if
                                       (= "NODEKEY" constraintType)
                                     (into [] constraintValue)
                                     constraintValue
                                     )
                                   ]
                     :constraintType (case constraintType
                                       ("UNIQUE" "NODEKEY") constraintType
                                       "EXISTANCE" (str constraintTarget constraintType)
                                       )
                     :execute? execute?
                     )
  )

(defn applyClassNeoConstraints
  "Apply all NeoConstraints for a class"
  [& {:keys [:className :execute?] :as keyArgs}]
  (let [builtQueries (reduceQueryColl
                      (map
                       #(apply applyClassNeoConstraint
                               (prepMapAsArg
                                (assoc
                                 (clojure.set/rename-keys
                                  (merge keyArgs
                                         (into {} ((% "neo") :properties))
                                         (into {} ((% "ncat") :properties))
                                         )
                                  {"constraintValue" :constraintValue
                                   "constraintType" :constraintType
                                   "constraintTarget" :constraintTarget
                                   }
                                  )
                                 :execute? false
                                 )
                                )
                               )
                       (((getClassNeoConstraints className) :results) 0)
                       )
                      )
        ]
    (if
        execute?
      (apply gdriver/runQuery builtQueries)
      builtQueries)
    )
  )

;;TODO : Rewrite class creation funtions

;; (defn createClassSQ
;;   ;; WARNING!!!!!!!!!!!!!!! NOT BUG FREE ... YET ... BUT SINGLE QUERY.... SO TRANSACTIONAL, ie SOMEWHAT SAFE. 

;;   "Creates a node with label Class (Sequentially in a cypher query).
;;   isAbstract : true or false.
;;   className : unique string.
;;   classType : either 'NODE' or 'RELATION'.
;;   superClasses : vector of classNames.
;;   _attributeTypes : vector of maps with keys '_name', '_datatype'
;;   newAttributeTypes : same.
;;   propertyMap : optional propertyMap.
;;   _constraintsVec : vector of maps with keys 'constraintType', 'constraintTarget', 'constraintValue'."
;;   [isAbstract? className classType superClasses _attributeTypes &[propertyMap newAttributeTypes _constraintsVec]]
;;   (let [attributeTypes (atom []) constraintsVec (atom [])]
;;     (doall (map (fn
;;                   [_constraint]
;;                   (if (not= classType (_constraint "constraintTarget"))
;;                     (throw (Exception. (str "ConstraintTarget should be same as classType" _constraint))))) _constraintsVec))
;;     (doall (map (fn
;;                   [superClass]
;;                   (let [fetchedClass (getNodesParsed "Class" {"className" superClass}) c_attributeTypes (atom []) c_constraintsVec (atom [])]
;;                     (if (empty? fetchedClass)
;;                       (throw (Exception. (str "Class Does not Exist: " superClass))))
;;                     (if (not= classType (((fetchedClass 0) :properties) "classType"))
;;                       (throw (Exception. (str "Superclass should have same classType as new Class: " superClass)))
;;                       (do
;;                         (reset! c_attributeTypes (getClassAttributeTypes superClass))
;;                         (reset! c_constraintsVec (getClassNeoConstraints superClass))
;;                         (reset! attributeTypes (vec (distinct (concat @attributeTypes @c_attributeTypes))))
;;                         (reset! constraintsVec (vec (distinct (concat @constraintsVec @c_constraintsVec)))))))) superClasses))
;;     (reset! attributeTypes (vec (distinct (concat @attributeTypes _attributeTypes))))
;;     (reset! constraintsVec (vec (distinct (concat @constraintsVec _constraintsVec))))
;;     (let [driver (getDriver) session (.session driver) fullSummary (atom nil) constraintValues (vec (map (fn [constraint] {"constraintValue" (constraint "constraintValue")}) @constraintsVec)) subConstraintsVec (vec (map (fn [constraint] (dissoc constraint "constraintValue")) @constraintsVec)) combinedPropertyMap (combinePropertyMap (merge {"C" (merge {"isAbstract" isAbstract? "className" className "classType" classType} propertyMap)} (addStringToMapKeys (into {} (map-indexed vector @attributeTypes)) "AT") (addStringToMapKeys (into {} (map-indexed vector newAttributeTypes)) "NAT") (addStringToMapKeys (into {} (map-indexed vector subConstraintsVec)) "NEOC") (addStringToMapKeys (into {} (map-indexed vector constraintValues)) "NEOCV") (addStringToMapKeys (into {} (map-indexed vector ((fn [] (let [newMaps (atom [])] (doall (map (fn [superClass] (swap! newMaps conj {"className" superClass})) superClasses)) @newMaps))))) "SUP"))) cypherQuery (atom nil)]
;;       (if (or (not (contains? #{"NODE" "RELATION"} classType)) (not (= "java.lang.Boolean" (.getName (type isAbstract?)))))
;;         (throw (Exception. "classType or isAbstract? arguments dont conform to their standards. Read DOC")))
;;       (reset! cypherQuery (str (if (= 0 (count (vec (concat @attributeTypes superClasses @constraintsVec)))) "" " MATCH ") (clojure.string/join ", " (map (fn
;;                                                                                                                                                             [attribute]
;;                                                                                                                                                             (let [atindex (.indexOf @attributeTypes attribute)]
;;                                                                                                                                                               (str "(AT" atindex ":AttributeType " ((combinedPropertyMap :propertyStringMap) (str atindex "AT")) ") "))) @attributeTypes)) (if (or (= 0 (count @attributeTypes)) (= 0 (count superClasses))) "" ", ")
;;                                (clojure.string/join ", " (map (fn
;;                                                                 [superClass]
;;                                                                 (let
;;                                                                     [supindex (.indexOf superClasses superClass)]
;;                                                                   (str "(SUP" supindex ":Class " ((combinedPropertyMap :propertyStringMap) (str supindex "SUP")) ") "))) superClasses)) (if (= 0 (count @constraintsVec)) "" ", ")
;;                                (clojure.string/join ", " (map (fn
;;                                                                 [constraint]
;;                                                                 (let [neocindex (.indexOf @constraintsVec constraint)]
;;                                                                   (str "(NEOC" neocindex ":NeoConstraint " ((combinedPropertyMap :propertyStringMap) (str neocindex "NEOC")) ") "))) @constraintsVec))
;;                                "CREATE (newClass:Class " ((combinedPropertyMap :propertyStringMap) "C") ")" (if (= 0 (count superClasses)) "" ", ")
;;                                (clojure.string/join ", " (map (fn
;;                                                                 [superClass]
;;                                                                 (let [supindex (.indexOf superClasses superClass)]
;;                                                                   (str "(newClass)-[:IsSubClassOf]->(SUP" supindex ")"))) superClasses)) (if (= 0 (count newAttributeTypes)) "" ", ")
;;                                (clojure.string/join ", " (map (fn
;;                                                                 [newAttribute]
;;                                                                 (let [natindex (.indexOf newAttributeTypes newAttribute)]
;;                                                                   (str " (NAT" natindex ":AttributeType " ((combinedPropertyMap :propertyStringMap) (str natindex "NAT")) ") ")
;;                                                                   )) newAttributeTypes)) (if (= 0 (count @attributeTypes)) "" ", ")
;;                                (clojure.string/join ", " (map (fn
;;                                                                 [attribute]
;;                                                                 (let [atindex (.indexOf @attributeTypes attribute)]
;;                                                                   (str "(newClass)-[HAT" atindex ":HasAttributeType]->(AT" atindex") "))) @attributeTypes)) (if (= 0 (count newAttributeTypes)) "" ", ")
;;                                (clojure.string/join ", " (map (fn
;;                                                                 [newAttribute]
;;                                                                 (let [natindex (.indexOf newAttributeTypes newAttribute)]
;;                                                                   (str "(newClass)-[NHAT" natindex ":HasAttributeType]->(NAT" natindex ")"))) newAttributeTypes)) (if (= 0 (count @constraintsVec)) "" ", ")
;;                                (clojure.string/join ", " (map (fn
;;                                                                 [constraint]
;;                                                                 (let [neocindex (.indexOf @constraintsVec constraint)]
;;                                                                   (str "(NEOC" neocindex ")-[NEOCAT" neocindex ":NeoConstraintAppliesTo " ((combinedPropertyMap :propertyStringMap) (str neocindex "NEOCV")) "]->(newClass)"))) @constraintsVec))))
;;       (reset! fullSummary (getCombinedFullSummary [(getFullSummary (.run session @cypherQuery (combinedPropertyMap :combinedPropertyMap))) (applyClassNeoConstraints className)]))
;;       (.close driver)
;;       @fullSummary)))

;; (defn addClassAT_tx
;;   "Adds a relation HasAttributeType from Class to AttributeType under transaction.
;;   tx: neo4j bolt transaction object or session object
;;   _atname: _name of AttributeType.
;;   _atdatatype: _datatype of AttributeType.
;;   className: className of Class"
;;   [tx className _atname _atdatatype]
;;   (if (not= 1 (count (getNodesParsed "AttributeType" {"_name" _atname "_datatype" _atdatatype})))
;;     (throw (Exception. (str "Unique AttributeType with _name: " _atname ", _datatype:" _atdatatype " not found"))))
;;   (let [fullSummary (atom nil) combinedPropertyMap (combinePropertyMap {"C" {"className" className} "AT" {"_name" _atname "_datatype" _atdatatype}})]
;;     (reset! fullSummary (getFullSummary (.run tx (str "MATCH (class:Class " ((combinedPropertyMap :propertyStringMap) "C") ") , (att:AttributeType " ((combinedPropertyMap :propertyStringMap) "AT") ") CREATE (class)-[:HasAttributeType]->(att)") (combinedPropertyMap :combinedPropertyMap))))
;;     @fullSummary))

;; (defn addClassAT
;;   "Adds a relation HasAttributeType from Class to AttributeType.
;;   _atname: _name of AttributeType.
;;   _atdatatype: _datatype of AttributeType.
;;   className: className of Class"
;;   [className _atname _atdatatype]
;;   (let [driver (getDriver) fullSummary (atom nil)]
;;     (reset! fullSummary (addClassAT_tx (.session driver) className _atname _atdatatype))
;;     (.close driver)
;;     @fullSummary))

;; (defn addClassNC_tx
;;   "Adds a relation NeoConstraintAppliesTo from Class to NeoConstraint under transaction.
;;   tx: neo4j bolt transaction object or session object.
;;   constraintType should be either of UNIQUE,EXISTANCE,NODEKEY.
;;   constraintTarget should be either of NODE,RELATION.
;;   constraintValue should be the AttributeType"
;;   [tx className _constraintType _constraintTarget _constraintValue]
;;   (let [fetchedClass (getNodesParsed "Class" {"className" className "classType" _constraintTarget}) fullSummary (atom nil) combinedPropertyMap (combinePropertyMap {"C" {"className" className} "NEOC" {"constraintType" _constraintType  "constraintTarget" _constraintTarget} "CV" {"constraintValue" _constraintValue}})]
;;     (if (not= 1 (count fetchedClass))
;;       (throw (Exception. (str "Unique Class with className:" className ", and classType:" _constraintTarget " not found"))))
;;     (reset! fullSummary (getFullSummary (.run tx (str "MATCH (class:Class " ((combinedPropertyMap :propertyStringMap) "C") ") , (neoc:NeoConstraint " ((combinedPropertyMap :propertyStringMap) "NEOC") ") CREATE (neoc)-[ncat:NeoConstraintAppliesTo " ((combinedPropertyMap :propertyStringMap) "CV") "]->(class)") (combinedPropertyMap :combinedPropertyMap))))
;;     @fullSummary))

;; (defn addClassNC
;;   "Adds a relation NeoConstraintAppliesTo from Class to NeoConstraint.
;;   constraintType should be either of UNIQUE,EXISTANCE,NODEKEY.
;;   constraintTarget should be either of NODE,RELATION.
;;   constraintValue should be the AttributeType"
;;   [className _constraintType _constraintTarget _constraintValue]
;;   (let [driver (getDriver) fullSummary (atom nil)]
;;     (reset! fullSummary (addClassNC_tx (.session driver) className _constraintType _constraintTarget _constraintValue))
;;     (.close driver)
;;     @fullSummary))

;; (defn addClassSup_tx
;;   "Adds a relation IsSubClassOf from one Class to another under transaction.
;;   tx: neo4j bolt transaction object or session object.
;;   className: className of subClass.
;;   supClassName: className of supClass"
;;   [tx className supClassName]
;;   (let [fullSummary (atom nil) combinedPropertyMap (combinePropertyMap {"C" {"className" className} "SUP" {"className" supClassName}})]
;;     (reset! fullSummary (getFullSummary (.run tx (str "MATCH (class:Class " ((combinedPropertyMap :propertyStringMap) "C") ") , (supClass:Class " ((combinedPropertyMap :propertyStringMap) "SUP") ") CREATE (class)-[:IsSubClassOf]->(supClass)") (combinedPropertyMap :combinedPropertyMap))))
;;     @fullSummary))

;; (defn addClassSup
;;   "Adds a relation IsSubClassOf from one Class to another.
;;   className: className of subClass.
;;   supClassName: className of supClass"
;;   [className supClassName]
;;   (let [driver (getDriver) fullSummary (atom nil)]
;;     (reset! fullSummary (addClassSup_tx (.session driver)  className supClassName))
;;     (.close driver)
;;     @fullSummary))

;; ;; For Some reason, when using transactions, the following function hangs. Set transactional? to true and uncomment to reproduce the error. Fair warning: DB becomes unusable.

;; (defn createClassFN
;;   "Creates a node with label Class (Functionally).
;;   transactional? : true or false, depending on whether creation should take place under a transaction or not
;;   isAbstract : true or false.
;;   className : unique string.
;;   classType : either 'NODE' or 'RELATION'.
;;   superClasses : vector of classNames.
;;   _attributeTypes : vector of maps with keys '_name', '_datatype'.
;;   propertyMap : optional propertyMap.
;;   _constraintsVec : vector of maps with keys 'constraintType', 'constraintTarget', 'constraintValue'."
;;   [transactional? isAbstract? className classType superClasses _attributeTypes propertyMap _constraintsVec]
;;   (let [attributeTypes (atom []) constraintsVec (atom [])]
;;     (if (or (not (contains? #{"NODE" "RELATION"} classType)) (not (= "java.lang.Boolean" (.getName (type isAbstract?)))))
;;         (throw (Exception. "classType or isAbstract? arguments dont conform to their standards. Read DOC")))
;;     (doall (map (fn
;;                   [superClass]
;;                   (let [fetchedClass (getNodesParsed "Class" {"className" superClass})]
;;                     (if (empty? fetchedClass)
;;                       (throw (Exception. (str "Class Does not Exist: " superClass))))
;;                     (if (not= classType (((fetchedClass 0) :properties) "classType"))
;;                       (throw (Exception. (str "Superclass should have same classType as new Class: " superClass))))
;;                     (reset! attributeTypes (vec (distinct (concat @attributeTypes (getClassAttributeTypes superClass)))))
;;                     (reset! constraintsVec (vec (distinct (concat @constraintsVec (getClassNeoConstraints superClass))))))) superClasses))
;;     (reset! attributeTypes (vec (distinct (concat @attributeTypes _attributeTypes))))
;;     (reset! constraintsVec (vec (distinct (concat @constraintsVec _constraintsVec))))
;;     (getCombinedFullSummary [(if transactional?
;;       (let [driver (getDriver) session (.session driver) trx (.beginTransaction session) summaries (atom [])]
;;         (try
;;           (swap! summaries conj (createNewNode_tx "Class" (merge {"className" className "classType" classType "isAbstract" isAbstract?} propertyMap) trx))
;;           (println "Class Created")
;;           (doall (map (fn [attributeType] (swap! summaries conj (addClassAT_tx trx className (attributeType "_name") (attributeType "_datatype")))) @attributeTypes))
;;           (println "AttributeTypes Created")
;;           (doall (map (fn [constraint] (swap! summaries conj (addClassNC_tx trx className (constraint "constraintType") (constraint "constraintTarget") (constraint "constraintValue")))) @constraintsVec))
;;           (println "NeoConstraints Created")
;;           (doall (map (fn [superClass] (swap! summaries conj (addClassSup_tx trx className superClass))) superClasses))
;;           (println "SuperClasses Added")
;;           (.success trx)
;;           (catch Exception E (do
;;                                (.failure trx)
;;                                (.printStackTrace E)
;;                                (.getMessage E)))
;;           (finally (do
;;                      (.close trx)
;;                      (.close session)
;;                      (.close driver))))
;;       	(getCombinedFullSummary @summaries))
;;       (getCombinedFullSummary (vec (concat [(createNewNode "Class" (merge {"className" className "classType" classType "isAbstract" isAbstract?}))] (vec (doall (map getCombinedFullSummary (vec (doall (pcalls (fn [] (vec (doall (pmap (fn [attributeType] (addClassAT className (attributeType "_name") (attributeType "_datatype"))) @attributeTypes)))) (fn [] (vec (doall (pmap (fn [constraint] (addClassNC className (constraint "constraintType") (constraint "constraintTarget") (constraint "constraintValue"))) @constraintsVec)))) (fn [] (vec (doall (pmap (fn [superClass] (addClassSup className superClass)) superClasses)))))))))))))) (applyClassNeoConstraints className)])))

;; (defn gnowdbInit
;;   "Create Initial constraints"
;;   []
;;   (getCombinedFullSummary [(createNCConstraints)
;;                            (createATConstraints)
;;                            (createCATConstraints)
;;                            (createClassConstraints)
;;                            (createAllNeoConstraints)]))

;; (defn validatePropertyMap
;;   "Validates a propertyMap for a class with className.
;;   Assumes class with given className exists"
;;   [className propertyMap]
;;   (let [classAttributeTypes (getClassAttributeTypes className) errors (atom [])]
;;     (if (> (count (keys propertyMap)) (count classAttributeTypes))
;;       (swap! errors conj  (str "No of properties (" (count (keys propertyMap)) ") > No of AttributeTypes (" (count classAttributeTypes) ")")))
;;     (doall (pmap (fn
;;                    [property]
;;                    (if (not= 1 (count (filter (fn [classAttributeType] (= classAttributeType {"_name" property "_datatype" (.getName (type (propertyMap property)))})) classAttributeTypes)))
;;                      (swap! errors conj (str "Unique AttributeType _name : " property ", _datatype : " (.getName (type (propertyMap property))) " not found for Class : " className)))) (keys propertyMap)))
;;     @errors))

;; (defn createNodeInstance
;;   "Creates a node , as an instance of a class with classType:NODE."
;;   [className propertyMap]
;;   (let [nodeClass (getNodesParsed "Class" {"className" className "classType" "NODE"})]
;;     (if (not= 1 (count nodeClass))
;;       (throw (Exception. (str "Unique Node Class with className:" className " ,classType:NODE doesn't exist")))
;;       (if (((nodeClass 0) :properties) "isAbstract")
;;         (throw (Exception. (str className " is Abstract"))))))
;;   (let [propertyErrors (validatePropertyMap className propertyMap)]
;;     (if (not= 0 (count propertyErrors))
;;       (throw (Exception. (str "PropertyMap is not valid : " propertyErrors)))))
;;   (createNewNode className propertyMap))

;; (defn createRelationInstance
;;   "Creates a relation between two nodes, as an instance of a class with classType:RELATION.
;;   fromClassName: className of 'out' label.
;;   fromPropertyMap: a property map that matches one or more 'out' nodes.
;;   propertyMap: relation propertyMap.
;;   toClassName: className of 'in' label.
;;   toPropertyMap: a property map that matches one or more 'in' nodes."
;;   [className fromClassName fromPropertyMap propertyMap toClassName toPropertyMap]
;;   (let [relClass (getNodesParsed "Class" {"className" className "classType" "RELATION"})]
;;     (if (not= 1 (count relClass))
;;       (throw (Exception. (str "Unique Relation Class with className:" className " ,classType:RELATION doesn't exist")))
;;       (if (((relClass 0) :properties) "isAbstract")
;;         (throw (Exception. (str className " is Abstract"))))))
;;   (if (not= 1 (count (getNodesParsed "Class" {"className" fromClassName "classType" "NODE"})))
;;     (throw (Exception. (str "Unique Node Class with className:" fromClassName " ,classType:NODE doesn't exist"))))
;;   (if (not= 1 (count (getNodesParsed "Class" {"className" toClassName "classType" "NODE"})))
;;     (throw (Exception. (str "Unique Node Class with className:" toClassName " ,classType:NODE doesn't exist"))))
;;   (let [propertyErrors (validatePropertyMap className propertyMap)]
;;     (if (not= 0 (count propertyErrors))
;;       (throw (Exception. (str "PropertyMap is not valid : " propertyErrors)))))
;;   (createRelation fromClassName fromPropertyMap className propertyMap toClassName toPropertyMap))
