(ns gnowdb.orientdb
  (:require [clojure.string :as clojure.string]
            [clojure.data.json :as clojure.data.json]))
(import [com.tinkerpop.blueprints.impls.orient OrientGraphFactory OrientGraph OrientVertex OrientEdge OrientVertexType OrientEdgeType]
        [com.orientechnologies.orient.core.sql OCommandSQL]
        [com.orientechnologies.orient.core.command.script OCommandScript]
        [com.tinkerpop.blueprints Direction])

(defn getOrientDBDetails
  "Get OrientDB Connection info"
  []
  ["remote:localhost/mydb" "admin" "admin"])

(defn connect
  "Connect to orientdb and get OrientGraphFactory Object"
  []
  (def orientDBDetails (getOrientDBDetails))
  (def graphFactory (OrientGraphFactory. (orientDBDetails 0) (orientDBDetails 1) (orientDBDetails 2)))
  graphFactory)

(defn execCommand
  "Executes SQL Command on OrientGraph Object"
  [^OrientGraph orientGraph
   ^String sqlCommand]
  (.execute (.command orientGraph (OCommandSQL. sqlCommand)) nil))

(defn execBatch
  "Executes SQL Batch on OrientGraph Object"
  [^OrientGraph orientGraph
   ^String sqlBatch]
  (.execute (.command orientGraph (OCommandScript. "sql" sqlBatch)) nil))

(defn getVertices
  "Get All Vertices from OrientGraphFactory object.
  Returns an Iterable"
  [^OrientGraphFactory orientDBGraphFactoryObject]
  (try
    (def orientGraph (.getNoTx orientDBGraphFactoryObject))
    (def vertices (.getVertices orientGraph))
    (catch Exception E (.toString E))
    (finally (.shutdown orientGraph)))
  vertices)

;; (defn createDCProperties
;;   "Create Dublin Core Metadata Properties for Class"
;;   [^OrientGraph orientGraph
;;    ^String className]
;;   (def DCProperties [{:name "Title" :type "STRING"} {:name "Creator" :type "STRING"} {:name "Subject" :type "STRING"} {:name "Description" :type "STRING"} {:name "Publisher" :type "STRING"} {:name "Contributor" :type "STRING"} {:name "Date" :type "DATE"} {:name "Type" :type "STRING"} {:name "Format" :type "STRING"} {:name "Identifier" :type "STRING"} {:name "Source" :type "STRING"} {:name "Language" :type "STRING"} {:name "Relation" :type "STRING"} {:name "Coverage" :type "STRING"} {:name "Rights" :type "STRING"}])
;;   (dorun (map (fn [DCP]
;;                 (execCommand orientGraph (str "CREATE PROPERTY " className "." (DCP :name) " IF NOT EXISTS " (DCP :type)))) DCProperties)))

;; (defn createDCGenClass
;;   "Create Generic Abstract DC Class with optional Dublin Core Metadata elements"
;;   []
;;   (def orientGF (connect))
;;   (def orientGraph (.getTx orientGF))
;;   (try
;;     (execCommand orientGraph "CREATE CLASS DCGen IF NOT EXISTS ABSTRACT")
;;     (createDCProperties orientGraph "DCGen")
;;     (catch Exception E
;;       (do
;;         (.printStackTrace E)
;;         (.getMessage E)))
;;     (finally (.shutdown orientGraph))))


(defn createClass
  "Create a Class."
  [^String className
   ^String superClass
   ^Boolean isAbstract]
  (def orientGF (connect))
  (def orientGraph (.getTx orientGF))
  (try
    (execCommand orientGraph (str "CREATE CLASS " className " IF NOT EXISTS " (if (= superClass "") "" (str "EXTENDS " superClass " ")) (if isAbstract " ABSTRACT" "")))
    (catch Exception E
      (do
        (.printStackTrace E)
        (.getMessage E)))
    (finally (.shutdown orientGraph))))

(defn setClassDescription
  "Edit the description of a class.
  descriptionMap should be a clojure map.
  classType is either 'vertex' or 'edge'"
  [classType className descriptionMap]
  (def orientGF (connect))
  (def orientGraph (.getTx orientGF))
  (try
    (def oClass (let [clName classType]
      (case clName
        "vertex" (.getVertexType orientGraph className)
        "edge" (.getEdgeType orientGraph className))))
    (.setDescription oClass (clojure.data.json/write-str descriptionMap))
    (.commit orientGraph)
    (catch Exception E
      (do
        (.printStackTrace E)
        (.getMessage E)
        (.rollback orientGraph)))
    (finally (.shutdown orientGraph))))

(defn getClassDescription
  "Edit the description of a class.
  classType is either 'vertex' or 'edge'"
  [classType className]
  (def orientGF (connect))
  (def orientGraph (.getTx orientGF))
  (try
    (def oClass (let [clName classType]
      (case clName
        "vertex" (.getVertexType orientGraph className)
        "edge" (.getEdgeType orientGraph className))))
    (def desc (clojure.data.json/read-json (clojure.string/replace (.getDescription oClass) #"\\" "")))
    (.commit orientGraph)
    (catch Exception E
      (do
        (.printStackTrace E)
        (.getMessage E)))
    (finally (.shutdown orientGraph)))
  desc)

(defn createConstraintString
  "Create Constraint String."
  [pconstraints]
  (def constraintStringVec [])
  (dorun (map (fn
         [pconstraint]
         (def constraintStringVec (conj constraintStringVec (str (pconstraint :cname) " " (if (= (pconstraint :ctype) "BOOLEAN") (pconstraint :cval) (str "\"" (pconstraint :cval) "\"")))))) pconstraints))
  (def constraintString (if (empty? constraintStringVec) (str "") (str "(" (clojure.string/join ", " constraintStringVec) ")")))
  constraintString)

(defn makePropertyListUnique
  "Creates a composite Index on a list of properties(as a vector) of a class to make them compositely unique.
  Index Name should be unique.
  Class and properties should already exist"
  [indexName className propertyListVec]
  (def orientGF (connect))
  (def orientGraph (.getNoTx orientGF))
  (try
    (if (empty? propertyListVec)
      (throw (Exception. "Property Vector Empty")))
    (execCommand orientGraph (str "CREATE INDEX " indexName " ON " className "(" (clojure.string/join ", " propertyListVec) ") UNIQUE"))
    (catch Exception E
      (do
        (.printStackTrace E)
        (.getMessage E)))
    (finally (.shutdown orientGraph))))

(defn createProperty
  "Create Property for a class."
  [className,propertyMap,orientGraph]
  (def constraintString)
  (def query (str "CREATE PROPERTY " className "." (propertyMap :pname) " IF NOT EXISTS " (propertyMap :pdatatype) " " (createConstraintString (propertyMap :pconstraints))))
  (execCommand orientGraph query))

(defn createProperties
  "Create properties and their constraints to an existing class.
  Properties should be given as Vector of Maps.
  Each Map is a property with mandatory keys:':pname',':pdatatype',':pconstraints'.
  ':pname' : String
  ':pdatatype' : One of orientdb datatypes listed here: 'https://orientdb.com/docs/2.2/Types.html'
  ':pconstraints' : A vector (can be empty) of maps with keys: ':cname', ':ctype', ':cval'
                   :cname can be one of : 'MIN', 'MAX', 'MANDATORY', 'READONLY', 'NOTNULL', 'REGEXP'
                   :ctype can be one of : 'STRING', 'BOOLEAN'.
                   :cval takes a value according to choice of :cname and :ctype
  
  'pUniques' : A vector (can be empty) of Maps with the following keys :':indexName', ':propertyListVec'"
  [className,propertyVec,pUniques]
  (def orientGF (connect))
  (def orientGraph (.getNoTx orientGF))
  (try
    (dorun (map (fn
                  [propertyMap]
                  (createProperty className propertyMap orientGraph)) propertyVec))
    (dorun (map (fn
                  [pUnique]
                  (makePropertyListUnique (pUnique :indexName) className (pUnique :propertyListVec))) pUniques))
    (catch Exception E (do
                         (.printStackTrace E)
                         (.getMessage E)))
    (finally (.shutdown orientGraph))))

(defn createATClass
  "Create AttributeType Vertex Class"
  []
  (createClass "AttributeType" "V" false)
  (createProperties "AttributeType" [{:pname "_name" :pdatatype "STRING" :pconstraints [{:cname "MANDATORY" :ctype "BOOLEAN" :cval "TRUE"} {:cname "MIN" :ctype "STRING" :cval "1"} {:cname "NOTNULL" :ctype "BOOLEAN" :cval "TRUE"}]} {:pname "_datatype" :pdatatype "STRING" :pconstraints [{:cname "MANDATORY" :ctype "BOOLEAN" :cval "TRUE"} {:cname "MIN" :ctype "STRING" :cval "1"} {:cname "NOTNULL" :ctype "BOOLEAN" :cval "TRUE"}]} {:pname "_subjectTypes" :pdatatype "EMBEDDEDLIST" :pconstraints []}] [{:indexName "_name_UNIQ_AT" :propertyListVec ["_name"]}]))

(defn createVCClass
  "Create ValueConstraint Vertex Class"
  []
  (createClass "ValueConstraint" "V" false)
  (createProperties "ValueConstraint" [{:pname "_cname" :pdatatype "STRING" :pconstraints [{:cname "MANDATORY" :ctype "BOOLEAN" :cval "TRUE"} {:cname "MIN" :ctype "STRING" :cval "1"} {:cname "NOTNULL" :ctype "BOOLEAN" :cval "TRUE"}]} {:pname "_ctype" :pdatatype "STRING" :pconstraints [{:cname "MANDATORY" :ctype "BOOLEAN" :cval "TRUE"} {:cname "MIN" :ctype "STRING" :cval "1"} {:cname "NOTNULL" :ctype "BOOLEAN" :cval "TRUE"}]}] [{:indexName "_cname_UNIQ_VC" :propertyListVec ["_cname"]}]))

(defn createVC
  "Create ValueConstraint"
  [_cname _ctype]
  (def orientGF (connect))
  (def orientGraph (.getTx orientGF))
  (try
    (def VCVertex (.addVertex orientGraph "class:ValueConstraint" (into-array ["_cname" _cname "_ctype" _ctype])))
    (.commit orientGraph)
    (catch Exception E
      (do
        (.printStackTrace E)
        (.getMessage E)
        (.rollback orientGraph)))
    (finally (.shutdown orientGraph))))

(defn createAllVC
  "Create all the ValueConstraints"
  []
  (def VCS [{:cname "MIN" :ctype "STRING"} {:cname "MAX" :ctype "STRING"} {:cname "REGEX" :ctype "STRING"} {:cname "MANDATORY" :ctype "BOOLEAN"} {:cname "NOTNULL" :ctype "BOOLEAN"} {:cname "READONLY" :ctype "BOOLEAN"}])
  (map (fn
         [VC]
         (createVC (VC :cname) (VC :ctype))) VCS))

(defn createCATClass
  "Create ConstraintAppliesTo Edge Class"
  []
  (createClass "ConstraintAppliesTo" "E" false)
  (createProperties "ConstraintAppliesTo" [{:pname "_cval" :pdatatype "STRING" :pconstraints [{:cname "MANDATORY" :ctype "BOOLEAN" :cval "TRUE"} {:cname "MIN" :ctype "STRING" :cval "1"} {:cname "NOTNULL" :ctype "BOOLEAN" :cval "TRUE"}]}] []))

(defn createAT
  "Create an AttributeType.
  _subjectTypes should be a comma Seperated list of NodeTypes.., eg 'NT1,NT2'.
  constraintsVec should be a vector of maps with keys: ':cname',':cval'
  ':cname' is the _cname value of desired ValueConstraint Vertex.
  ':cval' is the value of the constraint"
  [_name _datatype _subjectTypes constraintsVec]
  (def orientGF (connect))
  (def orientGraph (.getTx orientGF))
  (try
    (def sqlBatch (str "BEGIN\n"
                       "LET ATVertex = CREATE VERTEX AttributeType SET _name = '" _name "', _datatype = '" _datatype "', _subjectTypes = '" _subjectTypes "' \n"))
    (dorun
     (map (fn
            [VC]
            (def sqlBatch (str sqlBatch
                               "LET VCVertex = SELECT FROM ValueConstraint WHERE _cname = '" (VC :cname) "'\n"
                               "if($VCVertex.size()!=1){\n"
                               "ROLLBACK\n"
                               "RETURN 0\n"
                               "}\n"
                               "CREATE EDGE ConstraintAppliesTo FROM $VCVertex TO $ATVertex SET _cval = '" (VC :cval) "'\n"))) constraintsVec))
    (def sqlBatch (str sqlBatch
                       "COMMIT\n"
                       "RETURN 1\n"))
    (def ret (execBatch orientGraph sqlBatch))
    (if (= ret 0)
      (throw (Exception. "Errors occured while creating ValueConstraint for AttributeType. Do unique ValueConstraints exist ?")))
    (catch Exception E
      (do
        (.printStackTrace E)
        (.getMessage E)
        (.rollback orientGraph)))
    (finally (.shutdown orientGraph))))

(defn parseATs
  "Parses a vector of Vertices of Vertex Class AttributeType.
  Returns a vector of all properties of ATs including constraints by parsing ConstraintAppliesTo Edges 'IN' to the AT.
  OrientGraph object should be open."
  [ATVertices orientGraph]
  (def ATVs [])
  (try
    (dorun (map
            (fn
              [ATVertex]
              (def ATV (into {} (.getProperties ATVertex)))
              (def CATEdges (vec (iterator-seq (.iterator (.getEdges ATVertex Direction/IN (into-array ["ConstraintAppliesTo"]))))))
              (def ATConstraints [])
              (dorun (map
                      (fn
                        [CATEdge]
                        (def CAT (into {} (.getProperties CATEdge)))
                        (def VCVertex (.getOutVertex CATEdge))
                        (def VCV (into {} (.getProperties (.getVertex orientGraph (.getIdentity VCVertex)))))
                        (def ATConstraints (conj ATConstraints (merge CAT VCV))))
                      CATEdges))
              (def ATV (assoc ATV :constraints ATConstraints))
              (def ATVs (conj ATVs ATV)))
            ATVertices))
    (catch Exception E
      (do
        (.printStackTrace E)
        (.getMessage E))))
  ATVs)

(defn getATs
  "Get all Vertices of class AttributeType."
  []
  (def orientGF (connect))
  (def orientGraph (.getNoTx orientGF))
  (try
    (def ATVertices (vec (iterator-seq (.iterator (.getVerticesOfClass orientGraph "AttributeType")))))
    (def ATVs (parseATs ATVertices orientGraph))
    (catch Exception E
      (do
        (.printStackTrace E)
        (.getMessage E)))
    (finally (.shutdown orientGraph)))
  ATVs)
