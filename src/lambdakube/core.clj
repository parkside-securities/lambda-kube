(ns lambdakube.core
  (:require [loom.graph :refer [digraph]]
            [loom.alg :refer [topsort]]
            [clojure.string :as str]
            [yaml.core :as yaml]
            [clojure.java.shell :as sh])
  (:import (java.util.regex Pattern)))

(defn field-conj [m k v]
  (if (contains? m k)
    (update m k conj v)
    ;; else
    (assoc m k [v])))

(defn extract-additional [obj]
  (cond
    (map? obj) (let [obj (into {} (for [[k v] obj]
                                    [k (extract-additional v)]))
                     additions-from-fields (mapcat (fn [[k v]] (-> v meta :additional)) obj)
                     explicit-additions (:$additional obj)
                     additional (concat explicit-additions
                                        additions-from-fields)]
                 (with-meta (dissoc obj :$additional)
                   {:additional additional}))
    (sequential? obj) (let [obj (map extract-additional obj)
                            additional (mapcat #(-> % meta :additional) obj)]
                        (with-meta obj {:additional additional}))
    :else obj))

(defn pod
  ([name labels options]
   {:apiVersion "v1"
    :kind "Pod"
    :metadata {:name name
               :labels labels}
    :spec options})
  ([name labels]
   (pod name labels {})))

(defn deployment [pod replicas]
  (let [name (-> pod :metadata :name)
        labels (-> pod :metadata :labels)
        template (-> pod
                     (update :metadata dissoc :name)
                     (dissoc :apiVersion :kind))]
    {:apiVersion "apps/v1"
     :kind "Deployment"
     :metadata {:name name
                :labels labels}
     :spec {:replicas replicas
            :selector {:matchLabels labels}
            :template template}}))

(defn job
  ([pod restart-policy]
   (job pod restart-policy {}))
  ([pod restart-policy attrs]
   {:apiVersion "batch/v1"
    :kind "Job"
    :metadata {:name (-> pod :metadata :name)
               :labels (-> pod :metadata :labels)}
    :spec (merge {:template (-> pod
                                (dissoc :apiVersion :kind)
                                (update :metadata dissoc :name)
                                (update :spec assoc :restartPolicy restart-policy))}
                 attrs)}))

(defn stateful-set
  ([pod replicas options]
   (let [name (-> pod :metadata :name)
         labels (-> pod :metadata :labels)
         template (-> pod
                      (update :metadata dissoc :name)
                      (dissoc :apiVersion :kind))]
     {:apiVersion "apps/v1"
      :kind "StatefulSet"
      :metadata {:name name
                 :labels labels}
      :spec (-> options
                (merge {:replicas replicas
                        :selector
                        {:matchLabels labels}
                        :template template
                        :volumeClaimTemplates []}))}))
  ([pod replicas]
   (stateful-set pod replicas {})))

(defn config-map [name m]
  {:apiVersion "v1"
   :kind "ConfigMap"
   :metadata {:name name}
   :data m})

(defn secret-key-ref
  [k n optional?]
  {:secretKeyRef {:key k
                  :name n
                  :optional optional?}})

(defn add-container
  ([pod name image options]
   (let [container (-> options
                       (merge {:name name
                               :image image}))]
     (update pod :spec field-conj :containers container)))
  ([pod name image]
   (add-container pod name image {})))

(defn add-env [container envs]
  (let [envs (for [[name val] envs]
               {:name name
                :value val})]
    (-> container
        (update :env concat envs))))

(defn add-env-value-from [container envs]
  (let [envs (for [[name val] envs]
               {:name name
                :valueFrom val})]
    (-> container
        (update :env concat envs))))

(defn add-init-container
  ([pod name image options]
   (let [container (-> options
                       (merge {:name name
                               :image image}))]
     (update pod :spec field-conj :initContainers container)))
  ([pod name image]
   (add-init-container pod name image {})))

(defn update-container [pod cont-name f & args]
  (let [update-cont (fn [cont]
                      (if (= (:name cont) cont-name)
                        (apply f cont args)
                        ;; else
                        cont))]
    (-> pod
        (update-in [:spec :containers] #(map update-cont %))
        (update-in [:spec :initContainers] #(map update-cont %))
        (update :spec #(if (empty? (:initContainers %))
                         (dissoc % :initContainers)
                         ;; else
                         %)))))

(defn- mount-func [name mounts]
  (apply comp (for [[cont path] mounts]
                #(update-container % cont field-conj :volumeMounts
                                   {:name name
                                    :mountPath path}))))

(defn add-volume [pod name spec mounts]
  (-> pod
      (update :spec field-conj :volumes (-> {:name name}
                                            (merge spec)))
      ((mount-func name mounts))))

(defn add-files-to-container [pod cont unique base-path mounts]
  (let [relpathmap (into {} (for [[i [path val]] (map-indexed vector mounts)]
                              [(str "c" i) val]))
        items (vec (for [[i [path val]] (map-indexed vector mounts)]
                     {:key (str "c" i)
                      :path path}))]
    (-> pod
        (field-conj :$additional (config-map unique relpathmap))
        (add-volume unique {:configMap {:name unique
                                        :items items}}
                    {cont base-path}))))

(defn update-template [ctrl f & args]
  (apply update-in ctrl [:spec :template] f args))

(defn add-volume-claim-template [sset name spec mounts]
  (-> sset
      (update :spec field-conj :volumeClaimTemplates {:metadata {:name name}
                                                      :spec spec})
      (update-template (mount-func name mounts))))

(defn add-annotation [obj key val]
  (-> obj
      (update-in [:metadata :annotations] assoc key val)))

(defn expose [depl name portfunc attrs editfunc]
  (let [pod (-> depl :spec :template)
        srv {:kind "Service"
             :apiVersion "v1"
             :metadata {:name name}
             :spec (-> attrs
                       (merge {:selector (-> pod :metadata :labels)}))}
        [pod srv] (portfunc [pod srv editfunc])
        depl (-> depl
                 (field-conj :$additional srv)
                 (update :spec assoc :template pod))
        depl (if (= (:kind depl) "StatefulSet")
               (update depl :spec assoc :serviceName name)
               ;; else
               depl)]
    depl))

(defn expose-cluster-ip
  ([depl name portfunc]
   (expose-cluster-ip depl name portfunc {}))
  ([depl name portfunc attrs]
   (expose depl name portfunc (merge attrs {:type :ClusterIP})
           (fn [svc portname podport svcport]
             (update svc :spec field-conj :ports {:port svcport
                                                  :name portname
                                                  :targetPort portname})))))

(defn expose-headless
  ([depl name portfunc]
   (expose-headless depl name portfunc {}))
  ([depl name portfunc attrs]
   (expose-cluster-ip depl name portfunc (merge attrs {:clusterIP :None}))))

(defn expose-node-port [depl name portfunc]
  (expose depl name portfunc {:type :NodePort}
          (fn [svc portname podport svcport]
            (let [ports {:targetPort portname
                         :name portname
                         :port podport}
                  ports (if (nil? svcport)
                          ports
                          ;; else
                          (assoc ports :nodePort svcport))]
              (update svc :spec field-conj :ports ports)))))

(defn injector []
  {:rules []
   :descs []})

(defn rule [$ res deps func]
  (update $ :rules conj [func deps res]))

(defn- append [list obj]
  (if (sequential? obj)
    (concat list obj)
    ;; else
    (conj list obj)))

(defn- sorted-rules [rules]
  (let [rulemap (into {} (map-indexed vector rules))
        g (apply digraph (concat
                          ;; Inputs
                          (for [[index [func deps res]] rulemap
                                dep deps]
                            [dep index])
                          ;; Outputs
                          (for [[index [func deps res]] rulemap]
                            [index res])))]
    (map rulemap (topsort g))))

(defn- describe-single [api-obj descs]
  (->> descs
       (map (fn [f] (f api-obj)))
       (reduce merge {})))

(defn- describe [api-obj descs]
  (let [api-obj (extract-additional api-obj)
        objs (cons api-obj (-> api-obj meta :additional))]
    (->> objs
         (map #(describe-single % descs))
         (reduce merge {}))))

(defn matcher [m]
  (cond
    (fn? m) (let [arity (-> m class
                            .getDeclaredMethods
                            first
                            .getParameterTypes
                            count)]
              (case arity
                2 m
                1 (fn [node ctx]
                    (m node))
                (throw (Exception. (str "Invalid matcher arity: " arity)))))
    (map? m) (fn [node ctx]
               (every? identity (for [[k v] m]
                                  (let [node (merge ctx node)
                                        m' (matcher v)]
                                    (and (contains? node k)
                                         (m' (node k) (ctx k)))))))
    (set? m) (fn [node ctx]
               (every? (fn [m'] ((matcher m') node ctx)) m))
    (keyword? m) (fn [node ctx]
                   (cond
                     (map? node) (contains? node m)
                     (string? node) (= node (name m))
                     :else (= node m)))
    (instance? Pattern m) (fn [node ctx]
                            (if (string? node)
                              (not (nil? (re-matches m node)))
                              ;; else
                              false))
    :else (fn [node ctx]
            (= node m))))

(defn updater [u]
  (cond
    (fn? u) u
    (vector? u) (->> u reverse (apply comp))
    (map? u) (updater (vec (for [[k v] u]
                             #(update % k (updater v)))))
    :else (constantly u)))

(defn aug-rule [m u]
  (fn [node ctx]
    (if ((matcher m) node ctx)
      ((updater u) node)
      ;; else
      node)))

(defn aug-rule-comp [rules]
  (let [f (fn [ctx]
            (apply comp (for [rule (reverse rules)]
                          #(rule % ctx))))]
    (fn [node ctx]
      ((f ctx) node))))

(defn aug-rules [rules]
  (aug-rule-comp (map #(apply aug-rule %) rules)))

(defn apply-aug-rule [$ rule node]
  (let [rule' (fn rule' [node ctx]
                (let [node (rule node ctx)]
                  (loop [node node
                         walkers (:walkers $)]
                    (if (empty? walkers)
                      node
                      ;; else
                      (let [node ((first walkers) node rule' ctx)]
                        (recur node (rest walkers)))))))]
    (rule' node {})))

(defn get-deployable
  ([{:keys [rules descs]} config]
   (let [rules (sorted-rules rules)]
     (loop [rules rules
            config config
            out []]
       (if (empty? rules)
         out
         ;; else
         (let [rule (first rules)]
           (if (nil? rule)
             (recur (rest rules) config out)
             ;; else
             (let [[func deps res] rule
                   [out config] (if (every? (partial contains? config) deps)
                                  (if (contains? config res)
                                    (throw (Exception. (str "Conflicting prerequisites for resource " res)))
                                    ;; else
                                    (let [api-obj (apply func (map config deps))
                                          desc (describe api-obj descs)
                                          extracted (extract-additional api-obj)]
                                      [(concat out [extracted] (-> extracted meta :additional))
                                       (assoc config res desc)]))
                                  ;; else
                                  [out config])]
               (recur (rest rules) config out))))))))
  ([$ config rules]
   (let [deployable (get-deployable $ config)
         rule (aug-rules rules)]
     (map #(apply-aug-rule $ rule %) deployable))))

(defn desc [$ func]
  (update $ :descs conj func))

(defn to-yaml [v]
  (->> v
       (map #(yaml/generate-string % :dumper-options {:flow-style :block :scalar-style :plain}))
       (str/join "---\n")))

(defn kube-apply [content file]
  (when-not (and (.exists file)
                 (= (slurp file) content))
    (spit file content)
    (let [res (sh/sh "kubectl" "apply" "-f" (str file))]
      (when-not (= (:exit res) 0)
        (.delete file)
        (throw (Exception. (:err res)))))))

(defn port
  ([cont portname podport]
   (port cont portname podport nil))
  ([cont portname podport svcport]
   (fn [[pod svc edit-svc]]
     [(-> pod
          (update-container cont field-conj :ports {:containerPort podport
                                                    :name portname}))
      (-> svc
          (edit-svc portname podport svcport))
      edit-svc])))

(defn standard-descs [$]
  (-> $
      (desc (fn [obj]
              (when (contains? (:metadata obj) :annotations)
                (-> obj :metadata :annotations))))
      (desc (fn [svc]
              (when (= (:kind svc) "Service")
                {:hostname (-> svc :metadata :name name)
                 :ports (->> (for [{:keys [name port]} (-> svc :spec :ports)]
                               [name port])
                             (into {}))})))
      (update :walkers concat
              [(fn [node rule ctx]
                 (if (and (contains? node :spec)
                          (contains? (:spec node) :template))
                   (update-in node [:spec :template] rule (-> ctx
                                                              (merge {:kind "Pod"
                                                                      :metadata (:metadata node)})))
                   ;; else
                   node))
               (fn [node rule ctx]
                 (if (and (= (:kind (merge node ctx)) "Pod")
                          (contains? (:spec node) :containers))
                   (update-in node [:spec :containers] #(map (fn [c]
                                                               (rule c (-> ctx
                                                                           (merge {:kind "Container"})))) %))
                   ;; else
                   node))
               (fn [node rule ctx]
                 (if (and (= (:kind (merge node ctx)) "Pod")
                          (contains? (:spec node) :initContainers))
                   (update-in node [:spec :initContainers] #(map (fn [c]
                                                                   (rule c (-> ctx
                                                                               (merge {:kind "InitContainer"})))) %))
                   ;; else
                   node))])))

(defn extract-nodes [$ nodes]
  (loop [nodes nodes]
    (let [nodes' (set (apply concat (for [n nodes
                                          f (:extractors $)]
                                      (f n))))]
      (if (= nodes' nodes)
        nodes
        ;; else
        (recur nodes')))))
