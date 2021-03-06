(ns tupelo.datomic
  (:refer-clojure :exclude [update partition])
  (:require [datomic.api      :as d]
            [tupelo.core      :refer [truthy? safe-> it-> spy spyx spyxx grab any? keep-if]]
            [tupelo.schema    :as ts]
            [schema.core      :as s] )
  (:use clojure.pprint)
  (:gen-class))

;---------------------------------------------------------------------------------------------------
; Notes:
;
; EAVT makes Datomic a database of *facts*.  EAV is just a database of *state*.
; Relation:   A set of maps (possibly shortened to a vector)
; Tuple:      A (fixed-length) vector (usually one of a group)
; Value:      A primitive value like "Joe" or 42
;
;---------------------------------------------------------------------------------------------------
; #todo
; - Verify that on update, retraction of old & assertion of new both get same tx/timestamp
; - Each entity should have an :entity/type attr, populated by ident-vals like :entity.type/person,
;   :entity.type/address, etc.
; - Each entity.type should have an entity.type.*/invariants list of functions which must always be
;   true (integrity constraints).
; - Add "enum" like keyword attrs (not entities)
; - Look at seek-datoms & entid-at (Craig Andera StrangeLoop talk 2013)
; 
; So a an Entity of type :entity.type/person looks like:
;              <name>          <type>      <constraints/invariants>
;             :person/name     String    #{ <english alphabet> fn.2 ... }
;             :person/email    String    #{ <email constraints> fn.2 ... }
;             :person/phone    long      #{ <us=10 digits> fn.2 ... }
;             :entity/type      attr      :entity.type/person
; 
; Does then [?eid :entity/_type  :entity.type/person] yield a list of all "person" entities?
; 
; 
;---------------------------------------------------------------------------------------------------

; Prismatic Schema type definitions
(s/set-fn-validation! true)   ; #todo add to Schema docs

(def special-attrvals
 "A map that defines the set of permissible values for use in attribute definition.

  User-defined attributes are special entities in Datomic. They are stored in the :db.part/db
  partition, and are defined by special attributes that are built-in to Datomic (this is analgous to
  the special forms that are built-in to Clojure). The root attributes are named by the following
  keywords (all in the 'db' namespace):

    :db/id
    :db/ident
    :db/valueType
    :db/cardinality
    :db/unique
    :db/doc
    :db/index
    :db/fulltext
    :db/isComponent
    :db/noHistory

  For each of these special attributes, this map defines the permissible values used for specifying
  user-defined attributes. Most special attributes are defined by a set of permissible keyword
  values. Permissible values for other special attributes are defined by a predicate function.  "
  { :db/valueType
      #{ :db.type/keyword   :db.type/string   :db.type/boolean  :db.type/long     :db.type/bigint 
         :db.type/float     :db.type/double   :db.type/bigdec   :db.type/bytes 
         :db.type/instant   :db.type/uuid     :db.type/uri      :db.type/ref }

    :db/cardinality   #{ :db.cardinality/one :db.cardinality/many }

    :db/unique        #{ :db.unique/value :db.unique/identity }

  ; #todo - document & enforce types & values for these attrs:
  ;   :db/ident #(keyword? %)
  ;   :db/doc #(string? %)
  ;   :db/index #{ true false }
  ;   :db/fulltext #{ true false }
  ;   :db/isComponent #{ true false }
  ;   :db/noHistory #{ true false }
  } )

;---------------------------------------------------------------------------------------------------
; Core functions

(s/defn new-partition :- ts/KeyMap
  "Returns the tx-data to create a new partition in the DB. Usage:

    (td/transact *conn* 
      (partition ident)
    )
  "
  [ident :- s/Keyword]
  (when-not (keyword? ident)
    (throw (IllegalArgumentException. (str "attribute ident must be keyword: " ident ))))
  { :db/id                    (d/tempid :db.part/db) ; The partition :db.part/db is built-in to Datomic
    :db.install/_partition    :db.part/db   ; Ceremony so Datomic "installs" our new partition
    :db/ident                 ident } )     ; The "name" of our new partition

(s/defn new-attribute    :- ts/KeyMap
  "Returns the tx-data to create a new attribute in the DB.  Usage:

    (td/transact *conn* 
      (attribute ident value-type & options)
    )

   The first 2 params are required. Other params are optional and will use normal Datomic default
   values (false or nil) if omitted. An attribute is assumed to be :db.cardinality/one unless
   otherwise specified.  Optional values are:

      :db.unique/value
      :db.unique/identity
      :db.cardinality/one     <- assumed by default
      :db.cardinality/many
      :db/index
      :db/fulltext
      :db/isComponent
      :db/noHistory
      :db/doc                 <- *** currently unimplemented ***
  "
  [ ident       :- s/Keyword
    value-type  :- s/Any
   & options ]  ; #todo type spec?
  (when-not (keyword? ident)
    (throw (IllegalArgumentException. (str "attribute ident must be keyword: " ident ))))
  (when-not (truthy? (safe-> special-attrvals :db/valueType value-type))
    (throw (IllegalArgumentException. (str "attribute value-type invalid: " ident ))))
  (let [base-specs    { :db/id                  (d/tempid :db.part/db)
                        :db.install/_attribute  :db.part/db ; Datomic ceremony to "install" the new attribute
                        :db/cardinality         :db.cardinality/one   ; default value for most attrs
                        :db/ident               ident
                        :db/valueType           value-type }
        option-specs  (into (sorted-map)
                        (for [it options]
                          (cond 
                            (= it :db.unique/value)         {:db/unique :db.unique/value}
                            (= it :db.unique/identity)      {:db/unique :db.unique/identity}
                            (= it :db.cardinality/one)      {:db/cardinality :db.cardinality/one}
                            (= it :db.cardinality/many)     {:db/cardinality :db.cardinality/many}
                            (= it :db/index)                {:db/index true}
                            (= it :db/fulltext)             {:db/fulltext true}
                            (= it :db/isComponent)          {:db/isComponent true}
                            (= it :db/noHistory)            {:db/noHistory true}
                            (string? it)                    {:db/doc it}))) ; #todo finish this
        tx-specs      (into base-specs option-specs)
  ]
    tx-specs
  ))

; #todo need test
(s/defn new-entity  :- ts/KeyMap
  "Returns the tx-data to create a new entity in the DB. Usage:

    (td/transact *conn* 
      (new-entity attr-val-map)                 ; default partition -> :db.part/user 
      (new-entity partition attr-val-map)       ; user-specified partition
    )

   where attr-val-map is a Clojure map containing attribute-value pairs to be added to the new
   entity."
  ( [ attr-val-map    :- ts/KeyMap ]
   (new-entity :db.part/user attr-val-map))
  ( [ -partition      :- s/Keyword
      attr-val-map    :- ts/KeyMap ]
    (into {:db/id (d/tempid -partition) } attr-val-map)))

; #todo need test
(s/defn new-enum :- ts/KeyMap   ; #todo add namespace version
  "Returns the tx-data to create a new enumeration entity in the DB. Usage:

    (td/transact *conn*
      (new-enum ident)
    )

  where ident is the (keyword) name for the new enumeration entity.  "
  [ident :- s/Keyword]
  (when-not (keyword? ident)
    (throw (IllegalArgumentException. (str "attribute ident must be keyword: " ident ))))
  (new-entity {:db/ident ident} ))

; #todo  -  document entity-spec as EID or refspec in all doc-strings
; #todo  -  document use of "ident" in all doc-strings (EntityIdent?)
(s/defn update :- ts/KeyMap
  "Returns the tx-data to update an existing entity  Usage:

    (td/transact *conn*
      (update entity-spec attr-val-map)
    )

   where attr-val-map is a Clojure map containing attribute-value pairs to be added to the new
   entity.  For attributes with :db.cardinality/one, the previous value will be (automatically)
   retracted prior to the insertion of the new value. For attributes with :db.cardinality/many, the
   new value will be accumulated into the current set of values."
  [entity-spec    :- ts/EntitySpec
   attr-val-map   :- ts/KeyMap ]
    (into {:db/id entity-spec} attr-val-map))

(s/defn retract-value :- ts/Vec4
  "Returns the tx-data to retract an attribute-value pair for an entity. Only a single
   attribute-value pair can be retracted for each call to retract-value.  Usage:

    (td/transact *conn*
      (retract-value entity-spec attribute value)
    )

   where the attribute-value pair must exist for the entity or the retraction will fail.  " ; #todo verify
  [entity-spec  :- ts/EntitySpec
   attribute    :- s/Keyword
   value        :- s/Any ]
  [:db/retract entity-spec attribute value] )

(s/defn retract-entity :- ts/Vec2
  "Returns the tx-data to retract all attribute-value pairs for an entity, as well as all references
   to the entity by other entities. Usage:
   
    (td/transact *conn*
      (retract-entity entity-spec)
    )
   
  If the retracted entity refers to any other entity through an attribute with :db/isComponent=true,
  the referenced entity will be recursively retracted as well."
  [entity-spec  :- ts/EntitySpec ]
  [:db.fn/retractEntity entity-spec] )

; #todo need test
(s/defn transact :- s/Any  ; #todo
  "Like (datomic.api/transact ...) but does not require wrapping everything in a Clojure vector. Usage:
   
    (td/transact *conn*
      (td/new-entity attr-val-map)                 ; default partition -> :db.part/user 
      (td/update entity-spec-1 attr-val-map-1)
      (td/update entity-spec-2 attr-val-map-2))
   "
  [conn & tx-specs]
  (d/transact conn tx-specs))

;---------------------------------------------------------------------------------------------------
; Query

; #todo need checks to stop collection result (:find [?e ...])
; #todo and scalar result (:find [?e .])
(defmacro ^:no-doc query* ; #todo remember 'with'
  ; returns a HashSet of datomic entity objects
  "Base function for improved API syntax for datomic.api/q query function (Entity API)"
  [& args]
  (let [args-map    (apply hash-map args)
      ; _ (println args-map)
        let-vec     (grab :let args-map)
        let-map     (apply hash-map let-vec)
      ; _ (println let-map)
        let-syms    (keys let-map)
      ; _ (println let-syms)
        let-srcs    (vals let-map)
      ; _ (println let-srcs)
        find-vec    (grab :find args-map)
      ; _ (println \newline find-vec)
        where-vec   (grab :where args-map)
      ; _ (println where-vec)
  ]
    (when-not (vector? let-vec)
      (throw (IllegalArgumentException. (str "query*: value for :let must be a vector; received=" let-vec))))
    (when-not (vector? find-vec)
      (throw (IllegalArgumentException. (str "query*: value for :find must be a vector; received=" find-vec))))
    (when-not (vector? where-vec)
      (throw (IllegalArgumentException. (str "query*: value for :where must be a vector; received=" where-vec))))

   `(d/q  '{:find   ~find-vec
            :where  ~where-vec 
            :in     [ ~@let-syms ] }
        ~@let-srcs)
  ))

; #todo: rename :find -> :select or :return or :result ???
(defmacro query
  "Returns a TupleSet #{ [s/Any] } of query results, where each tuple is unique. Usage:

    (td/query   :let    [$        (d/db *conn*)
                         ?name    \"James Bond\"]
                :find   [?e]
                :where  [ [?e :person/name ?name] ] )

  Unlike datomic.api/q, the query form does not need to be wrapped in a map literal nor is any
  quoting required. Most importantly, the :in keyword has been replaced with the :let keyword, and
  the syntax has been copied from the Clojure let special form so that both the query variables (the
  implicit DB $ in this case) are more closely aligned with their actual values. Also, the implicit
  DB $ must be explicitly tied to its data source in all cases (as shown above).  "
  [& args]
  `(into #{} 
      (for [tuple# (query* ~@args) ]
        (into [] tuple#))))

(defmacro query-set
  "Returns a Set of unique scalar query results (i.e. #{s/Any}). Any duplicate values will be
   discarded. Usage:

    (td/query-set :let    [$        (d/db *conn*) 
                           ?name    \"James Bond\"]
                  :find   [?e]
                  :where  [ [?e :person/name ?name] ] )
  "
  [& args]
  `(into #{}
      (for [tuple# (query* ~@args)]
        (if (= 1 (count tuple#)) 
          (first tuple#)
          (throw (IllegalStateException.
                  (str "query-set: tuple must hold only one item: " tuple#)))))))

(defmacro query-tuple
  "Returns a single Tuple (i.e. [s/Any]) of query results. Usage:

    (td/query-tuple :let    [$ (d/db *conn*)]
                    :find   [?eid ?name] ; <- output tuple shape
                    :where  [ [?eid :person/name ?name      ]
                              [?eid :location    \"Caribbean\"] ] )

   It is an error if more than one tuple is found.
  "
  [& args]
  `(let [result-set# (query* ~@args) ]
      (if (= 1 (count result-set#))
        (into [] (first result-set#))
        (throw (IllegalStateException.
                (str "query-tuple: result-set must hold only one tuple: " result-set#))))))

(defmacro query-scalar
  "Returns a scalar query result (i.e. s/Any).  Usage:
   
    (td/query-scalar  :let    [$      (d/db *conn*)
                               ?name  \"James Bond\"]
                      :find   [?eid]
                      :where  [ [?eid :person/name ?name] ] )

   It is an error if more than one tuple is found or if the tuple contains more than one scalar
   value.  "
  [& args]
  `(let [tuple# (query-tuple ~@args) ] ; retrieve the single-tuple result
      (if (= 1 (count tuple#))
        (first tuple#)
        (throw (IllegalStateException.
          (str "query-scalar: tuple must hold a single item: " tuple#))))))

(defn- ^:no-doc contains-pull?  ; prevent codox ("lein doc") from processing 
  "Returns true if a sequence of symbols includes 'pull'"
  [args-vec]
; (println \newline "contains-pull?" args-vec)
  (let [args-map    (apply hash-map args-vec)
        find-vec    (flatten [ (grab :find args-map) ] ) ]
  ; (spyxx find-vec)
  ; (doseq [item find-vec]
  ;   (do (print " ") (pr item) ))
  ; (newline)
    (any? #(= 'pull %) find-vec)))

(defmacro query-pull
  "Returns a TupleList [Tuple] of query results, where items may be duplicated. Intended only for
   use with the Datomic Pull API. Usage:

     (td/query-pull  :let    [$ (d/db *conn*) ]
                     :find   [ (pull ?eid [:location]) ]
                     :where  [ [?eid :location] ] )

   It is an error if the :find clause does not contain a Datomic Pull API request.  "
  [& args]
; (println "query-pull" args)
  (assert (tupelo.datomic/contains-pull? args)
          "query-pull: Only intended for queries using the Datomic Pull API")
; (println "query-pull: past assert")
  `(do 
      (into []
          (for [tuple# (query* ~@args)]
            (into [] tuple#)))))

; #todo: write blog post/forum letter about this testing technique
(defn t-query
  "Test the query macro, returns true on success."
  []
  (let [expanded-result
          (macroexpand-1 '(tupelo.datomic/query*  :let    [a  (src 1)  
                                                           b  val-2]
                                                  :find   [?e]
                                                  :where  [ [?e :person/name ?name] ] ))
  ]
    (= expanded-result
       '(datomic.api/q (quote {:find [?e], 
                               :where [[?e :person/name ?name]], 
                               :in [a b]}) 
                       (src 1) val-2) )))


;---------------------------------------------------------------------------------------------------
; Informational functions

; #todo - need test
(s/defn entity-map :- ts/KeyMap
  "Returns a map of an entity's attribute-value pairs. A simpler, eager version of datomic/entity."
  [db-val         :- datomic.db.Db
   entity-spec    :- ts/EntitySpec ]
  (into {} (d/entity db-val entity-spec)))

; #todo - need test
(s/defn eid->ident :- s/Keyword
  "Returns the keyword ident value given an EID value"
  [db-val     :- s/Any  ; #todo
   eid-val    :- ts/Eid]
  (d/q '{:find  [?ident .]
         :in    [$ ?eid]
         :where [ [?eid :db/ident ?ident] ] }
       db-val eid-val ))

; #todo - need test
(s/defn datom-map :- ts/DatomMap
  "Returns a plain of Clojure map of an datom's attribute-value pairs. 
   A datom map is structured as:

      { :e        entity id (eid)
        :a        attribute eid
        :v        value
        :tx       transaction eid
        :added    true/false (assertion/retraction) }
   "
  [datom :- s/Any]  ; #todo
  { :e            (:e     datom)
    :a      (long (:a     datom)) ; must cast Integer -> Long
    :v            (:v     datom)  ; #todo - add tests to catch changes
    :tx           (:tx    datom)
    :added        (:added datom) } )

; #todo - need test
; #todo - make non-lazy?
(s/defn datoms :- [ ts/DatomMap ]
  "Returns a lazy sequence of Clojure maps of an datom's attribute-value pairs. 
   A datom map is structured as:

      { :e        entity id (eid)
        :a        attribute eid
        :v        value
        :tx       transaction eid
        :added    true/false (assertion/retraction) }

   Like (datomic.api/datoms ...), but returns a seq of plain Clojure maps.  "
  [db             :- s/Any
   index          :- s/Keyword
   & components ]  ; #todo
  (for [datom (apply d/datoms db index components) ]
    (datom-map datom)))

; #todo - need test
(s/defn tx-datoms :- s/Any
  "Returns a vector of datom-maps from a TxResult"
  [db-val     :- s/Any  ; #todo
   tx-result  :- ts/TxResult ]
  (let [tx-data       (:tx-data tx-result)  ; a seq of datoms
        fn-datom      (fn [arg]
                        (let [datom1  (datom-map arg)
                              attr-eid    (:a datom1)
                              attr-ident  (eid->ident db-val attr-eid)
                              datom2  (assoc datom1 :a attr-ident)
                        ]
                          datom2 ))
        tx-datoms      (mapv fn-datom tx-data)
    ]
      tx-datoms ))

; #todo - need test
(s/defn partition-name :- s/Keyword
  "Returns the partition name (the :db/ident value) for an Entity"
  [db-val       :- datomic.db.Db
   entity-spec  :- ts/EntitySpec ]
  (d/ident db-val (d/part entity-spec)))

; #todo - need test
(s/defn is-transaction? :- s/Bool
  "Returns true if an entity is a transaction (i.e. it is in the :db.part/tx partition)"
  [db-val   :- s/Any
   entity-spec  :- ts/EntitySpec ]
  (= :db.part/tx (partition-name db-val entity-spec)))

; #todo - need test
(s/defn transactions :- [ ts/KeyMap ]
  "Returns a lazy sequence of entity-maps for all DB transactions"
  [db-val :- s/Any]
  (let [candidate-eids    (map :e (datoms db-val :aevt :db/txInstant))
            ; All transaction entities must have attr :db/txInstant
        tx-eids           (filter #(is-transaction? db-val %) candidate-eids) 
            ; filter in case any user entities have attr :db/txInstant
        result            (map #(entity-map db-val %) tx-eids) ]
    result))

; #todo need test
(s/defn eids :- [ts/Eid]
  "Returns a collection of the EIDs created in a transaction."
  [tx-result :- ts/TxResult]
  (vals (grab :tempids tx-result)))

(s/defn txid  :- ts/Eid
  "Returns the EID of a transaction"
  [tx-result :- ts/TxResult]
  (let [datoms  (grab :tx-data tx-result)
        txids   (mapv :tx datoms) ] 
    (assert (apply = txids))  ; all datoms in tx have same txid
    (first txids)))           ; return the first one


;---------------------------------------------------------------------------------------------------
; #todo: make helper fn's for rule creation
; (def-rule <name> [args]
;   [?com-eid    :community/neighborhood   ?nbr]          ; rule clause
;   [?nbr        :neighborhood/district    ?dist]         ; rule clause
;   [?dist       :district/region          ?reg]          ; rule clause
;   [?reg        :db/ident                 ?reg-ident] ]  ; rule clause
;
; literal way:
; (let[ rules-list   '[ [ (com-region ?com-eid ?reg-ident) ; rule header
;                         [?com-eid    :community/neighborhood   ?nbr]          ; rule clause
;                         [?nbr        :neighborhood/district    ?dist]         ; rule clause
;                         [?dist       :district/region          ?reg]          ; rule clause
;                         [?reg        :db/ident                 ?reg-ident] ]  ; rule clause
;                     ]
;---------------------------------------------------------------------------------------------------
; #todo: make helper fn's for enum invarient enforcement (no random entities assigned)
;---------------------------------------------------------------------------------------------------
; Pull stuff
; #todo:  pull-one
; #todo:  pull-many
; #todo:  pull-deep (pull-recursive) ; need a limit?

