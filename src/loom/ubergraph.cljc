(ns loom.ubergraph
  (:require [loom.graph :as lg]
            [loom.attr :as la]
            [clojure.string :as str]
            [#?(:clj clojure.pprint :cljs cljs.pprint) :as pprint]))

; NEW CONCEPTS

; There are three new protocols above and beyond what is in Loom:
; UndirectedGraph for undirected graphs
; QueryableGraph for attribute graphs and multigraphs
; MixedDirectionGraph for graphs which support a mixture of directed and undirected edges
;    within the same graph

; For undirected graph algorithms, it is useful to know the connection between the
; two sides of an undirected edge, so we can keep attributes and weight in sync
; and add/remove or otherwise mark them in sync.
(defprotocol UndirectedGraph
  (other-direction [g edge] "Returns the other direction of this edge in graph g"))

; We need a way to retrieve edges that is friendly to both regular and multi-graphs,
; but especially important for multi-graphs.
(defprotocol QueryableGraph
  (find-edges [g src dest] [g query] "Returns all edges that match the query")
  (find-edge [g src dest] [g query] "Returns first edge that matches the query"))

; Sample queries
; (find-edges g {:src 1, :dest 3, :color :red})
;    finds all edges from 1 to 3 with :color :red in edge attributes
;
; (find-edges g {:src 1, :weight 5})
;    finds all edges from 1 with a weight of 5
;
; (find-edges g {:dest :Chicago, :airline :Delta, :time :afternoon})
;    finds all edges leading to :Chicago with :airline :Delta and
;    :time :afternoon in edge attributes

; It would be nice to have a way to incorporate both undirected and directed edges
; into the same graph structure.

(defprotocol MixedDirectionEdgeTests
  (undirected-edge? [e] "Is e one 'direction' of an undirected edge?")
  (directed-edge? [e] "Is e a directed edge?")
  (mirror-edge? [e] "Is e the mirrored half of the undirected edge?"))

(defprotocol MixedDirectionGraph
  (add-directed-edges* [g edges] "Adds directed edges regardless of the graph's undirected/directed default")
  (add-undirected-edges* [g edges] "Adds undirected edges regardless of the graph's undirected/directed default"))

; Improved attribute handling

(defprotocol Attrs
  (add-attrs [g node-or-edge attribute-map] [g n1 n2 attribute-map] "Merges an attribute map with the existing attributes of a node or edge")
  (set-attrs [g node-or-edge attribute-map] [g n1 n2 attribute-map] "Sets the attribute map of a node or edge, overwriting existing attribute map")
  (remove-attrs [g node-or-edge attributes] [g n1 n2 attributes] "Removes the attributes from the node or edge"))

; Path protocols

(defprotocol IPath
  "All the things you can do to a path"
  (edges-in-path [path] "A list of edges comprising the path")
  (nodes-in-path [path] "A list of nodes comprising the path")
  (cost-of-path [path] "Returns the cost of the path with respect to the property that was minimized
in the search that produced this path.")
  (start-of-path [path] "Returns the first node in the path")
  (end-of-path [path] "Returns the last node in the path"))

(defprotocol IAllPathsFromSource
  "An object that knows how to produce paths on demand from a given source,
  using path-to"
  (path-to [paths dest] "The shortest path to dest"))

(defprotocol IAllPaths
  "An object that knows how to produce paths on demand between any pair of nodes,
  using path-between"
  (path-between [paths src dest] "The shortest path between src and dest"))

; Recognizing Ubergraphs

(defprotocol IUbergraph "Is it an Ubergraph?"
  (ubergraph? [g]))


; We extend add-edges to support attribute maps in the edge specification
; so let's update the doc string
(alter-meta! #'lg/add-edges assoc
             :doc "Adds edges to graph g of the form [n1 n2], [n1 n2 weight], or [n1 n2 attr-map].")

; This namespace provides a concrete implementation of ubergraph.protocols, which is
; a conservative extension to Loom's protocols.

; It supports undirected, directed, weighted, attributes, editing, mixed directedness,
; and multiple edges between a given pair of vertices.

; If Loom adopts the protocol extensions proposed in ubergraph.protocols, then
; this data structure will be compatible with all loom algorithms.

; At the bottom of this file, I demonstrate how one can use Ubergraph to implement
; all the graph types provided by Loom (except Flygraph).  There are a couple
; subtle implementation details that are different between the Ubergraph implementation
; and the Loom default implementations of the protocols.

; 1. Edge constructors (such as add-edges) support either [src dest], or [src dest weight]
;    or [src dest attribute-map].
;
; 2. By default, edges added with the [src dest weight] constructor simply store the weight
;    as a :weight attribute.  This is simply an implementation detail which can be completely
;    ignored if you don't have any attributes and simply want to use the weight protocol
;    to retrieve the weight of an edge.  But by making it an attribute, it has the
;    added benefit that you can alter the weight of an edge using the attribute protocol.
;
; 3. The edges that are returned by edges are not simple vectors, they are a custom Edge
;    data structure.  All the functions that consume edges can take this custom Edge
;    data structure, or simpler forms like [src dest] if that is enough to uniquely
;    identify the edge.  It is recommended that edge-processing algorithms access the
;    source and destination nodes using the new Edge protocol (src and dest), rather
;    than assuming that an edge is a vector.
;
; 4. The build-graph semantics are somewhat different from Loom's. Since Ubergraphs
;    are capable of holding both directed and undirected edges, if you build a
;    directed graph from an undirected graph, those edges are imported as undirected,
;    and conversely, if you build an undirected graph from a directed graph, those edges
;    are imported as directed.


; These are the functions that are too lengthy to define inline
; in the Ubergraph record.
(declare transpose-impl get-edge find-edges-impl find-edge-impl
         add-node add-edge remove-node remove-edge
         edge-description->edge resolve-node-or-edge
         force-add-directed-edge force-add-undirected-edge)

(defrecord Ubergraph [node-map allow-parallel? undirected? attrs]
  lg/Graph
  (nodes [g] (keys (:node-map g)))
  (edges [g] (for [[node node-info] (:node-map g)
                   [dest edges] (:out-edges node-info),
                   edge edges]
               (with-meta edge g)))
  (has-node? [g node] (boolean (get-in g [:node-map node])))
  (has-edge? [g n1 n2] (boolean (seq (find-edges g n1 n2))))
  (successors* [g node] (distinct (map lg/dest (lg/out-edges g node))))
  (out-degree [g node] (get-in g [:node-map node :out-degree]))
  (out-edges [g node] (map #(with-meta % g) (apply concat (vals (get-in g [:node-map node :out-edges])))))

  lg/Digraph
  (predecessors* [g node] (map lg/src (lg/in-edges g node)))
  (in-degree [g node] (get-in g [:node-map node :in-degree]))
  (in-edges [g node] (map #(with-meta % g) (apply concat (vals (get-in g [:node-map node :in-edges])))))
  (transpose [g] (transpose-impl g))

  lg/WeightedGraph
  ; Ubergraphs by default store weight in an attribute :weight
  ; Using an attribute allows us to modify the weight with the AttrGraph protocol
  (weight* [g e] (get-in g [:attrs (:id (edge-description->edge g e)) :weight] 1))
  (weight* [g n1 n2] (get-in g [:attrs (:id (get-edge g n1 n2)) :weight] 1))

  lg/EditableGraph
  (add-nodes* [g nodes] (reduce add-node g nodes))
  ; edge definition should be [src dest] or [src dest weight] or [src dest attribute-map]
  (add-edges* [g edge-definitions] (reduce (fn [g edge] (add-edge g edge)) g edge-definitions))
  (remove-nodes* [g nodes] (reduce remove-node g nodes))
  (remove-edges* [g edges] (reduce remove-edge g edges))
  (remove-all [g]
    (assoc g :attrs {}
             :node-map {}))

  la/AttrGraph
  (add-attr [g node-or-edge k v]
    (assoc-in g [:attrs (resolve-node-or-edge g node-or-edge) k] v))
  (add-attr [g n1 n2 k v] (la/add-attr g (get-edge g n1 n2) k v))
  (remove-attr [g node-or-edge k]
    (update-in g [:attrs (resolve-node-or-edge g node-or-edge)] dissoc k))
  (remove-attr [g n1 n2 k] (la/remove-attr g (get-edge g n1 n2) k))
  (attr [g node-or-edge k]
    (get-in g [:attrs (resolve-node-or-edge g node-or-edge) k]))
  (attr [g n1 n2 k] (la/attr g (get-edge g n1 n2) k))
  (attrs [g node-or-edge]
    (get-in g [:attrs (resolve-node-or-edge g node-or-edge)] {}))
  (attrs [g n1 n2] (la/attrs g (get-edge g n1 n2)))

  Attrs
  (add-attrs [g node-or-edge attribute-map]
    (update-in g [:attrs (resolve-node-or-edge g node-or-edge)]
               merge attribute-map))
  (add-attrs [g n1 n2 attribute-map]
    (add-attrs g (get-edge g n1 n2) attribute-map))
  (set-attrs [g node-or-edge attribute-map]
    (assoc-in g [:attrs (resolve-node-or-edge g node-or-edge)] attribute-map))
  (set-attrs [g n1 n2 attribute-map]
    (set-attrs g (get-edge g n1 n2) attribute-map))

  (remove-attrs [g node-or-edge attributes]
    (let [m (la/attrs g node-or-edge)]
      (assoc-in g [:attrs (resolve-node-or-edge g node-or-edge)]
                (apply dissoc m attributes))))
  (remove-attrs [g n1 n2 attributes]
    (remove-attrs g (get-edge g n1 n2) attributes))

  UndirectedGraph
  (other-direction [g edge]
    (when (undirected-edge? edge)
      (let [edge (edge-description->edge g edge),
            e (assoc edge :src (:dest edge) :dest (:src edge) :mirror? (not (:mirror? edge)))]
        e)))

  QueryableGraph
  (find-edges [g edge-query] (find-edges-impl g edge-query))
  (find-edges [g src dest] (find-edges-impl g src dest))
  (find-edge [g edge-query] (find-edge-impl g edge-query))
  (find-edge [g src dest] (find-edge-impl g src dest))

  MixedDirectionGraph
  (add-directed-edges* [g edge-definitions] (reduce (fn [g edge] (force-add-directed-edge g edge))
                                                    g edge-definitions))
  (add-undirected-edges* [g edge-definitions] (reduce (fn [g edge] (force-add-undirected-edge g edge))
                                                      g edge-definitions))

  IUbergraph
  (ubergraph? [g] true))

(defn undirected-graph? "If true, new edges in g are undirected by default.  If false,
  new edges in g are directed by default."
  [g] (:undirected? g))

(defn allow-parallel-edges? "If true, two edges between the same pair of nodes in the same direction
  are permitted.  If false, adding a new edge between the same pair of nodes as an existing edge will
  merge the edges into a single edge, and adding an undirected edge on top of an existing directed edge
  will `upgrade' the directed edge to undirected and merge attributes."
  [g] (:allow-parallel? g))

; A node-id is anything the user wants it to be -- a number, a keyword, a data structure
; An edge is something with a src, a dest, and an id that can be used to look up attributes

; node-map is a {node-id node-info}
; node-info is a {:out-edges {dest-id #{edge}} :in-edges {src-id #{edge}}
;                 :in-degree number :out-degree number}
; edge is either Edge or UndirectedEdge

(defrecord NodeInfo [out-edges in-edges out-degree in-degree])
(defrecord Edge [id src dest]
  lg/Edge
  (src [edge] src)
  (dest [edge] dest)
  MixedDirectionEdgeTests
  (undirected-edge? [e] false)
  (directed-edge? [e] true)
  (mirror-edge? [e] false)
  #?@(:cljs
      [IIndexed
       (-nth [e i] (case i 0 src 1 dest 2 (la/attr (meta e) e :weight) nil))
       (-nth [e i notFound] (case i 0 src 1 dest 2 (la/attr (meta e) e :weight) notFound))]
      :clj
      [clojure.lang.Indexed
       (nth [e i] (case i 0 src 1 dest 2 (la/attr (meta e) e :weight) nil))
       (nth [e i notFound] (case i 0 src 1 dest 2 (la/attr (meta e) e :weight) notFound))]))

; An UndirectedEdge stores an additional field that signals whether this was the
; original direction that was added to the graph, or the "mirror" edge that was
; automatically added to go in the reverse direction.  This is a useful concept
; because in some undirected graph algorithms, you only want to consider each
; edge once, so the mirror? field lets you filter out these duplicate reverse edges.

(defrecord UndirectedEdge [id src dest mirror?]
  lg/Edge
  (src [edge] src)
  (dest [edge] dest)
  MixedDirectionEdgeTests
  (undirected-edge? [e] true)
  (directed-edge? [e] false)
  (mirror-edge? [e] mirror?)
  #?@(:cljs
      [IIndexed
       (-nth [e i] (case i 0 src 1 dest 2 (la/attr (meta e) e :weight) nil))
       (-nth [e i notFound] (case i 0 src 1 dest 2 (la/attr (meta e) e :weight) notFound))]
      :clj
      [clojure.lang.Indexed
       (nth [e i] (case i 0 src 1 dest 2 (la/attr (meta e) e :weight) nil))
       (nth [e i notFound] (case i 0 src 1 dest 2 (la/attr (meta e) e :weight) notFound))]))


#?(:clj
   (extend-type
     Object
     MixedDirectionEdgeTests
     (undirected-edge? [e] false)
     (directed-edge? [e] false)
     (mirror-edge? [e] false)
     IUbergraph
     (ubergraph? [g] (and (satisfies? lg/Graph g)
                          (satisfies? lg/Digraph g)
                          (satisfies? lg/WeightedGraph g)
                          (satisfies? lg/EditableGraph g)
                          (satisfies? la/AttrGraph g)
                          (satisfies? Attrs g)
                          (satisfies? UndirectedGraph g)
                          (satisfies? QueryableGraph g)
                          (satisfies? MixedDirectionGraph g)))))


(defn edge?
  "Tests whether o is an edge object"
  [o] (or (instance? Edge o) (instance? UndirectedEdge o)))

(defn- get-edge [g n1 n2] (first (find-edges g n1 n2)))

(defn- add-node
  [g node]
  (cond
    (get-in g [:node-map node]) g  ; node already exists
    :else (assoc-in g [:node-map node] (->NodeInfo {} {} 0 0))))

(defn- add-node-with-attrs
  "Adds node to g with a given attribute map. Takes a [node attribute-map] pair."
  [g [node attr-map]]
  (add-attrs (add-node g node) node attr-map))

(defn add-nodes-with-attrs*
  "Takes a sequence of [node attr-map] pairs, and adds them to graph g."
  [g nodes-with-attrs]
  (reduce add-node-with-attrs g nodes-with-attrs))

(defn add-nodes-with-attrs
  "Takes any number of [node attr-map] pairs, and adds them to graph g."
  [g & nodes-with-attrs]
  (add-nodes-with-attrs* g nodes-with-attrs))

(defn- remove-node
  [g node]
  (-> g
    (lg/remove-edges* (lg/out-edges g node))
    (lg/remove-edges* (lg/in-edges g node))
    (update-in [:node-map] dissoc node)))

(def ^:private fconj (fnil conj #{}))
(def ^:private finc (fnil inc 0))

(defn- submap? [m1 m2]
  (every? identity (for [[k v] m1]
                     (= (get m2 k) v))))

(defn- find-edges-impl
  ([g src dest]
   (get-in g [:node-map src :out-edges dest]))
  ([g {src :src dest :dest :as attributes}]
   (let [edges
         (cond
           (and src dest) (get-in g [:node-map src :out-edges dest])
           src (lg/out-edges g src)
           dest (lg/in-edges g dest)
           :else (lg/edges g))
         attributes (dissoc attributes :src :dest)]
     (if (pos? (count attributes))
       (for [edge edges
             :when (submap? attributes (get-in g [:attrs (:id edge)]))]
         edge)
       edges))))

(defn- find-edge-impl [& args]
  (first (apply find-edges-impl args)))

;; TODO: don't really need value attribute
(defn- add-directed-edge [g src dest attributes]
  (let [g (-> g (add-node src) (add-node dest))
        edge-id [src dest (dissoc attributes :src dest)]
        edge (->Edge edge-id src dest)
        new-attrs (if attributes
                    (assoc (:attrs g) edge-id attributes)
                    (:attrs g))
        node-map (:node-map g)
        node-map-src (get node-map src)
        node-map-dest (get node-map dest)
        new-node-map-src (-> node-map-src
                             (update-in [:out-edges dest] fconj edge)
                             (update-in [:out-degree] finc))
        new-node-map-dest (-> (if (= src dest) new-node-map-src node-map-dest)
                              (update-in [:in-edges src] fconj edge)
                              (update-in [:in-degree] finc))
        new-node-map (assoc node-map src new-node-map-src dest new-node-map-dest)]
    (assoc g :node-map new-node-map
             :attrs new-attrs)))

(defn- add-undirected-edge [g src dest attributes]
  (let [g (-> g (add-node src) (add-node dest))
        forward-edge-id (conj (vec (sort [src dest]))
                              (dissoc attributes :src :dest))
        backward-edge-id forward-edge-id
        ;; TODO: don't store mirror, just return >
        forward-edge (->UndirectedEdge forward-edge-id src dest (not= src (first forward-edge-id)))
        backward-edge (->UndirectedEdge backward-edge-id dest src (= src (first forward-edge-id)))
        new-attrs (if attributes
                    (assoc (:attrs g) forward-edge-id attributes)
                    (:attrs g))
        node-map (:node-map g)
        node-map-src (get node-map src)
        node-map-dest (get node-map dest)
        new-node-map-src (-> node-map-src
                             (update-in [:out-edges dest] fconj forward-edge)
                             (update-in [:in-edges dest] fconj backward-edge)
                             (update-in [:in-degree] finc)
                             (update-in [:out-degree] finc))
        new-node-map-dest (-> (if (= src dest) new-node-map-src node-map-dest)
                              (update-in [:out-edges src] fconj backward-edge)
                              (update-in [:in-edges src] fconj forward-edge)
                              (update-in [:in-degree] finc)
                              (update-in [:out-degree] finc))
        new-node-map (assoc node-map src new-node-map-src dest new-node-map-dest)]
    (assoc g :node-map new-node-map
             :attrs new-attrs)))

(defn- number->map [n]
  (if (number? n) {:weight n} n))

(defn- add-edge
  [g [src dest attributes]]
  (let [attributes (number->map attributes)]
    (cond
      (and (not (:allow-parallel? g)) (get-edge g src dest))
      (if attributes
        (update-in g [:attrs (:id (get-edge g src dest))]
                   merge attributes)
        g)

      (:undirected? g)
      (add-undirected-edge g src dest attributes)

      :else
      (add-directed-edge g src dest attributes))))

(defn- force-add-directed-edge
  [g [src dest attributes]]
  (let [attributes (number->map attributes)]
    (cond
      (and (not (:allow-parallel? g)) (get-edge g src dest))
      (if attributes
        (update-in g [:attrs (:id (get-edge g src dest))]
                   merge attributes)
        g)
      :else (add-directed-edge g src dest attributes))))

(defn- force-add-undirected-edge
  [g [src dest attributes]]
  (let [attributes (number->map attributes)]
    (cond
      (and (not (:allow-parallel? g)) (or (get-edge g src dest)
                                       (get-edge g dest src)))
      (let [new-attrs (merge (la/attrs g src dest) (la/attrs g dest src) attributes)]
        (-> g
          (lg/remove-edges* [[src dest] [dest src]])
          (add-undirected-edge src dest attributes)))
      :else (add-undirected-edge g src dest attributes))))

(defn edge-description->edge
  "Many ubergraph functions can take either an *edge description* (i.e., [src dest]
  [src dest weight] or [src dest attribute-map]) or an actual edge object.  This function
  is used to convert edge descriptions into an edge object, or passing through an edge
  object unchanged, so regardless of what you pass in, you're guaranteed to get out
  an edge object."
  [g ed]
  (cond
    (edge? ed) ed
    (not (vector? ed)) (throw (IllegalArgumentException.
                                (str "Invalid edge description: " ed)))
    (= (count ed) 2) (find-edge g (ed 0) (ed 1))
    (= (count ed) 3)
    (cond (number? (ed 2))
          (find-edge g {:src (ed 0), :dest (ed 1), :weight (ed 2)})
          (map? (ed 2))
          (find-edge g (assoc (ed 2) :src (ed 0) :dest (ed 1)))
          :else
          (throw (IllegalArgumentException.
                   (str "Invalid edge description: " ed))))))

(defn- resolve-node-or-edge
  "Similar to edge-description->edge in that it converts edge descriptions to edge objects,
  but this function also passes nodes through unchanged, and extracts the edge id if
  it is an edge."
  [g node-or-edge]
  (cond (edge? node-or-edge) (:id node-or-edge)
        (lg/has-node? g node-or-edge) node-or-edge
        :else
        (try (:id (edge-description->edge g node-or-edge))
          (catch IllegalArgumentException e
            (throw (IllegalArgumentException. (str "Invalid node or edge description: " node-or-edge)))))))

(defn- remove-edge
  [g edge]
  ; Check whether edge exists before deleting
  (let [{:keys [src dest id] :as edge} (edge-description->edge g edge)]
    (if (get-in g [:node-map src :out-edges dest edge])
      (if-let
        [reverse-edge (other-direction g edge)]
        (-> g
          (update-in [:attrs] dissoc id)
          (update-in [:node-map src :out-edges dest] disj edge)
          (update-in [:node-map src :in-edges dest] disj reverse-edge)
          (update-in [:node-map src :in-degree] dec)
          (update-in [:node-map src :out-degree] dec)
          (update-in [:node-map dest :out-edges src] disj reverse-edge)
          (update-in [:node-map dest :in-edges src] disj edge)
          (update-in [:node-map dest :in-degree] dec)
          (update-in [:node-map dest :out-degree] dec))
        (-> g
          (update-in [:attrs] dissoc id)
          (update-in [:node-map src :out-edges dest] disj edge)
          (update-in [:node-map src :out-degree] dec)
          (update-in [:node-map dest :in-edges src] disj edge)
          (update-in [:node-map dest :in-degree] dec)))
      g)))

(defn- swap-edge [edge]
  (assoc edge :src (:dest edge) :dest (:src edge)))

(defn- transpose-impl [{:keys [node-map attrs] :as g}]
  (let [new-node-map
        (into {} (for [[node {:keys [in-edges out-edges in-degree out-degree]}] node-map
                       :let [new-in-edges (into {} (for [[k v] out-edges] [k (set (map swap-edge v))])),
                             new-out-edges (into {} (for [[k v] in-edges] [k (set (map swap-edge v))]))]]
                   [node (NodeInfo. new-out-edges new-in-edges in-degree out-degree)])),
        new-attrs (into {} (for [[o attr] attrs]
                             (if (edge? o) [(swap-edge o) attr] [o attr])))]
    (assoc g :node-map new-node-map
             :attrs new-attrs)))

(defn add-directed-edges
  "Adds directed edges, regardless of whether the underlying graph is directed or undirected"
  [g & edges]
  (add-directed-edges* g edges))

(defn add-undirected-edges
  "Adds directed edges, regardless of whether the underlying graph is directed or undirected"
  [g & edges]
  (add-undirected-edges* g edges))

(defn- strip-equal-id-edges
  ([inits] (strip-equal-id-edges (seq inits) #{}))
  ([inits seen-ids]
   (when inits
     (let [init (first inits)]
       (cond
         (edge? init) (if (seen-ids (:id init))
                        (recur (next inits) seen-ids)
                        (cons init (lazy-seq (strip-equal-id-edges
                                               (next inits)
                                               (conj seen-ids (:id init))))))
         :else (cons init (lazy-seq (strip-equal-id-edges
                                      (next inits)
                                      seen-ids))))))))

(defn- nodes-with-attrs [g]
  (for [n (lg/nodes g)] [n (la/attrs g n)]))

(defn- build [g init]
  (cond
    ;; ubergraph
    (instance? Ubergraph init)
    (let [new-g (add-nodes-with-attrs* g (nodes-with-attrs init)),
          directed-edges (for [e (lg/edges init)
                               :when (directed-edge? e)]
                           [(lg/src e) (lg/dest e) (la/attrs init e)])
          undirected-edges (for [e (lg/edges init),
                                 :when (and (undirected-edge? e)
                                            (not (mirror-edge? e)))]
                             [(lg/src e) (lg/dest e) (la/attrs init e)])
          new-g (add-directed-edges* new-g directed-edges)
          new-g (add-undirected-edges* new-g undirected-edges)]
      new-g)

    ;; Edge objects
    (directed-edge? init)
    (-> g
        (lg/add-nodes (lg/src init) (lg/dest init)),
        (add-directed-edges [(lg/src init) (lg/dest init)
                             (la/attrs (meta init) init)]))

    (undirected-edge? init)
    (-> g
        (lg/add-nodes (lg/src init) (lg/dest init))
        (add-undirected-edges [(lg/src init) (lg/dest init)
                               (la/attrs (meta init) init)]))

    ;; Marked as a node
    (:node (meta init))
    (add-node g init)

    ;; Marked as an edge
    (:edge (meta init))
    (let [[src dest n] init]
      (add-edge g [src dest (number->map n)]))

    ;; Adjacency map
    (map? init)
    (let [es (if (map? (val (first init)))
               (for [[n nbrs] init
                     [nbr wt] nbrs]
                 [n nbr wt])
               (for [[n nbrs] init
                     nbr nbrs]
                 [n nbr]))]
      (-> g
          (lg/add-nodes* (keys init))
          (lg/add-edges* es)))

    ;; node-with-attributes
    (and (vector? init) (= 2 (count init)) (map? (init 1)))
    (add-node-with-attrs g [(init 0) (init 1)])

    ;; edge description
    (and (vector? init) (#{2,3} (count init)))
    (add-edge g [(init 0) (init 1) (number->map (get init 2))])

    ;; node
    :else (add-node g init)))

(defn build-graph
  "Builds graphs using node descriptions of the form node-label or [node-label attribute-map]
  and edge descriptions of the form [src dest], [src dest weight], or [src dest attribute-map].
  Also can build from other ubergraphs, ubergraph edge objects, and from adjacency maps.

  Use ^:node and ^:edge metadata to resolve ambiguous inits, or build your graph with the more
  precise add-nodes, add-nodes-with-attrs, and add-edges functions."
  [g & inits]
  (reduce build g (strip-equal-id-edges inits)))

;; All of these graph options can also serve as weighted graphs, just initialize accordingly.

(defn multigraph
  "Multigraph constructor. See build-graph for description of valid inits"
  [& inits]
  (apply build-graph (->Ubergraph {} true true {}) inits))

(defn multidigraph
  "Multidigraph constructor. See build-graph for description of valid inits"
  [& inits]
  (apply build-graph (->Ubergraph {} true false {}) inits))

(defn graph
  "Graph constructor. See build-graph for description of valid inits"
  [& inits]
  (apply build-graph (->Ubergraph {} false true {}) inits))

(defn digraph
  "Digraph constructor. See build-graph for description of valid inits"
  [& inits]
  (apply build-graph (->Ubergraph {} false false {}) inits))

(defn ubergraph
  "General ubergraph construtor. Takes booleans for allow-parallel? and undirected? to
  call either graph, digraph, multigraph, or multidigraph.
  See build-graph for description of valid inits"
  [allow-parallel? undirected? & inits]
  (apply build-graph (->Ubergraph {} allow-parallel? undirected? {}) inits))

;; Serialize/deserialize to an edn Clojure data structure

(defn ubergraph->edn [g]
  {:allow-parallel? (:allow-parallel? g),
   :undirected? (:undirected? g),
   :nodes (vec (for [node (lg/nodes g)] [node (la/attrs g node)]))
   :directed-edges (vec (for [edge (lg/edges g) :when (directed-edge? edge)]
                             [(lg/src edge) (lg/dest edge) (la/attrs g edge)]))
   :undirected-edges (vec (for [edge (lg/edges g) :when (and (undirected-edge? edge) (not (mirror-edge? edge)))]
                               [(lg/src edge) (lg/dest edge) (la/attrs g edge)]))})

(defn edn->ubergraph [{:keys [allow-parallel? undirected? nodes directed-edges undirected-edges]}]
  (-> (ubergraph allow-parallel? undirected?)
      (add-nodes-with-attrs* nodes)
      (add-directed-edges* directed-edges)
      (add-undirected-edges* undirected-edges)))

;; Override print-dup so we can serialize to a string with (binding [*print-dup* true] (pr-str my-graph))
;; Deserialize from string with read-string.

(defmethod print-dup loom.ubergraph.Ubergraph [o w]
  (print-ctor o (fn [o w]
                  (print-dup (:node-map o) w)
                  (.write w " ")
                  (print-dup (:allow-parallel? o) w)
                  (.write w " ")
                  (print-dup (:undirected? o) w)
                  (.write w " ")
                  (print-dup (:attrs o) w))
                w))

;; Friendlier printing

(defn- graph-type [g]
  (cond
    (and (:allow-parallel? g) (:undirected? g)) "Multigraph"
    (:allow-parallel? g) "Multidigraph"
    (:undirected? g) "Graph"
    :else "Digraph"))

(defn count-nodes
  "Counts how many nodes are in g"
  [g]
  (if (instance? Ubergraph g)
    (count (:node-map g))
    (count (lg/nodes g))))

(defn count-edges
  "Counts how many edges are in g.
  Undirected edges are counted twice, once for each direction."
  [g]
  (apply + (for [node (lg/nodes g)]
             (lg/out-degree g node))))

(defn count-unique-edges
  "Counts how many edges are in g.
  Undirected edges are counted only once."
  [g]
  (count (for [edge (lg/edges g)
               :when (not (mirror-edge? edge))]
           edge)))

(defn pprint
  "Pretty print an ubergraph"
  [g]
  (println (graph-type g))
  (println (count-nodes g) "Nodes:")
  (doseq [node (lg/nodes g)]
    (println \tab node (let [a (la/attrs g node)] (if (seq a) a ""))))
  (println (count-unique-edges g) "Edges:")
  (doseq [edge (lg/edges g)]
    (cond
      (directed-edge? edge)
      (println \tab (lg/src edge) "->" (lg/dest edge)
               (let [a (la/attrs g edge)]
                 (if (seq a) a "")))
      (and (undirected-edge? edge) (not (mirror-edge? edge)))
      (println \tab (lg/src edge) "<->" (lg/dest edge)
               (let [a (la/attrs g edge)]
                 (if (seq a) a ""))))))

;; For Codox, don't want to document these constructors
(alter-meta! #'->Edge assoc :no-doc true)
(alter-meta! #'->NodeInfo assoc :no-doc true)
(alter-meta! #'->Ubergraph assoc :no-doc true)
(alter-meta! #'->UndirectedEdge assoc :no-doc true)
(alter-meta! #'map->Edge assoc :no-doc true)
(alter-meta! #'map->NodeInfo assoc :no-doc true)
(alter-meta! #'map->UndirectedEdge assoc :no-doc true)


;; Visualization

; Dorothy doesn't like attribute maps with values other than numbers, strings, and keywords

(defn- valid-dorothy-id? [x]
  (or (keyword? x) (string? x) (number? x)))

(defn- remove-invalids-from-map [m]
  (into (empty m) (for [[k v] m :when (and (valid-dorothy-id? k)
                                           (valid-dorothy-id? v))]
                    [k v])))

(defn- sanitize-attrs [g i]
  (remove-invalids-from-map (la/attrs g i)))

; Dorothy has a bug - it doesn't escape backslashes, so we do it here
(defn- escape-backslashes [s] (clojure.string/replace s "\\" "\\\\"))

(defn- label [g]
  (as-> g $
    (reduce
      (fn [g n]
        (la/add-attr g n :label (str (if (keyword? n) (name n) n)
                                     \newline
                                     (escape-backslashes (with-out-str (pprint/pprint (la/attrs g n)))))))
      $
      (lg/nodes g))
    (reduce
      (fn [g e]
        (if (not (mirror-edge? e))
          (la/add-attr g e :label (escape-backslashes (with-out-str (pprint/pprint (la/attrs g e)))))
          g))
      $
      (lg/edges g))))

(defn- dotid [n]
  (if (or (string? n)
          (keyword? n)
          (number? n))
    n
    (str/replace (print-str n) ":" "")))

#_(defn viz-graph
    "Uses graphviz to generate a visualization of your graph. Graphviz
must be installed on your computer and in your path. Passes along
to graphviz the attributes on the nodes and edges, so graphviz-related
attributes such as color, style, label, etc. will be respected.

Takes an optional map which can contain:
:auto-label true (labels each node/edge with its attribute map)
:layout :dot, :neato, :fdp, :sfdp, :twopi, or :circo
:save {:filename _, :format _} where format is one of
  :bmp :eps :gif :ico :jpg :jpeg :pdf :png :ps :ps2 :svgz :tif :tiff :vmlz :wbmp
Additionally map can contain graph attributes for graphviz like :bgcolor, :label, :splines, ..."
    ([g] (viz-graph g {}))
    ([g {layout :layout {filename :filename format :format :as save} :save
         auto-label :auto-label
         :as opts
         :or {layout :dot}}]
     (let [g (if auto-label (label g) g)
           ns (nodes g),
           es (edges g)
           nodes (for [n ns]
                   [(dotid n)
                    (sanitize-attrs g n)]),
           directed-edges (for [e es :when (directed-edge? e)]
                            [(dotid (src e)) (dotid (dest e)) (sanitize-attrs g e)])
           undirected-edges (for [e es :when (and (undirected-edge? e)
                                                  (not (mirror-edge? e)))]
                              [(dotid (src e)) (dotid (dest e))
                               (merge {:dir :none} (sanitize-attrs g e))])]
       (-> (concat [(merge {:layout layout} (dissoc opts :layout :save :auto-label))]
                   nodes directed-edges undirected-edges)
         d/digraph
         d/dot
         (cond->
           save (d/save! filename {:format format})
           (not save) d/show!)))))
