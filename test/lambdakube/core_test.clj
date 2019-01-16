(ns lambdakube.core-test
  (:require [midje.sweet :refer :all]
            [lambdakube.core :as lk]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]))

;; # Basic API Object Functions

;; The following functions create basic API objects.

;; ## Pod

;; The `pod` function creates a pod with no containers.
(fact
 (lk/pod :foo {:app :bar})
 => {:apiVersion "v1"
     :kind "Pod"
     :metadata {:name :foo
                :labels {:app :bar}}
     :spec {}})

;; `pod` can take a third argument with additional spec parameters.
(fact
 (lk/pod :foo {:app :bar} {:foo :bar})
 => {:apiVersion "v1"
     :kind "Pod"
     :metadata {:name :foo
                :labels {:app :bar}}
     :spec {:foo :bar}})

;; ## Deployment

;; The `deployment` function creates a deployment, based on the given
;; pod as template. The deployment takes its name from the given pod,
;; and removes the name from the template.
(fact
 (-> (lk/pod :foo {:bar :baz})
     (lk/add-container :bar "some-image")
     (lk/deployment 3))
 => {:apiVersion "apps/v1"
     :kind "Deployment"
     :metadata {:name :foo
                :labels {:bar :baz}}
     :spec {:replicas 3
            :selector
            {:matchLabels {:bar :baz}}
            :template
            {:metadata
             {:labels {:bar :baz}}
             :spec
             {:containers
              [{:name :bar
                :image "some-image"}]}}}})

;; ## Stateful Set

;; The `stateful-set` function wraps the given pod with a Kubernetes
;; stateful set.
(fact
 (-> (lk/pod :foo {:bar :baz})
     (lk/add-container :bar "some-image")
     (lk/stateful-set 5))
 => {:apiVersion "apps/v1"
     :kind "StatefulSet"
     :metadata {:name :foo
                :labels {:bar :baz}}
     :spec {:replicas 5
            :selector
            {:matchLabels {:bar :baz}}
            :template
            {:metadata
             {:labels {:bar :baz}}
             :spec
             {:containers
              [{:name :bar
                :image "some-image"}]}}
            :volumeClaimTemplates []}})

;; ## Job

;; The `job` function wraps a pod with a Kubernetes job. It takes a
;; pod to wrap, and a `:restartPolicy` parameter, which needs to be
;; either `:Never` or `:OnFailure`.
(fact
 (-> (lk/pod :my-job {:app :foo})
     (lk/add-container :bar "some-image")
     (lk/job :Never))
 => {:apiVersion "batch/v1"
     :kind "Job"
     :metadata {:labels {:app :foo}
                :name :my-job}
     :spec {:template {:metadata {:labels {:app :foo}}
                       :spec {:restartPolicy :Never
                              :containers [{:image "some-image" :name :bar}]}}}})

;; An optional `attrs` parameter takes additional attributes to be
;; placed in the job's `:spec`.
(fact
 (-> (lk/pod :my-job {:app :foo})
     (lk/add-container :bar "some-image")
     (lk/job :OnFailure {:backoffLimit 5}))
 => {:apiVersion "batch/v1"
     :kind "Job"
     :metadata {:labels {:app :foo}
                :name :my-job}
     :spec {:template {:metadata {:labels {:app :foo}}
                       :spec {:containers [{:image "some-image" :name :bar}]
                              :restartPolicy :OnFailure}}
            :backoffLimit 5}})

;; ## Config Map

;; The `config-map` function creates a Kubernetes configmap out of a
;; Clojure map.
(fact
 (lk/config-map :my-map {"config.conf" (lk/to-yaml [{:foo :bar}])})
 => {:apiVersion "v1"
     :kind "ConfigMap"
     :metadata {:name :my-map}
     :data {"config.conf" "foo: bar\n"}})

;; # Modifier Functions

;; The following functions augment basic API objects by adding
;; content. They always take the API object as a first argument.

;; ## Add Functions

;; The `add-container` function adds a container to a pod. The
;; function takes the container name and the image to be used as
;; explicit parameters, and an optional map with additional parameters.
(fact
 (-> (lk/pod :foo {})
     (lk/add-container :bar "bar-image" {:ports [{:containerPort 80}]}))
 => {:apiVersion "v1"
     :kind "Pod"
     :metadata {:name :foo
                :labels {}}
     :spec {:containers [{:name :bar
                          :image "bar-image"
                          :ports [{:containerPort 80}]}]}})

;; `add-env` augments the parameters of a _container_, and adds an
;; environment variable binding.
(fact
 (-> (lk/pod :foo {})
     (lk/add-container :bar "bar-image" (-> {:ports [{:containerPort 80}]}
                                            (lk/add-env {:FOO "BAR"}))))
 => {:apiVersion "v1"
     :kind "Pod"
     :metadata {:name :foo
                :labels {}}
     :spec {:containers [{:name :bar
                          :image "bar-image"
                          :ports [{:containerPort 80}]
                          :env [{:name :FOO
                                 :value "BAR"}]}]}})

;; If an `:env` key already exists, new entries are added to the list.
(fact
 (-> (lk/pod :foo {})
     (lk/add-container :bar "bar-image" (-> {:env [{:name :QUUX :value "TAR"}]}
                                            (lk/add-env {:FOO "BAR"}))))
 => {:apiVersion "v1"
     :kind "Pod"
     :metadata {:name :foo
                :labels {}}
     :spec {:containers [{:name :bar
                          :image "bar-image"
                          :env [{:name :QUUX
                                 :value "TAR"}
                                {:name :FOO
                                 :value "BAR"}]}]}})

;; `add-env-value-from` adds environment variable bindings with key reference as the value.
(fact
 (-> (lk/pod :foo {})
     (lk/add-container :bar "bar-image" (-> {:ports [{:containerPort 80}]}
                                            (lk/add-env-value-from {:FOO (lk/secret-key-ref "aKey" "aName" false)}))))
 => {:apiVersion "v1"
     :kind "Pod"
     :metadata {:name :foo
                :labels {}}
     :spec {:containers [{:name :bar
                          :image "bar-image"
                          :ports [{:containerPort 80}]
                          :env [{:name :FOO
                                 :valueFrom {:secretKeyRef {:key "aKey", :name "aName", :optional false}}}]}]}})

;; `add-init-container` adds a new [init container](https://kubernetes.io/docs/concepts/workloads/pods/init-containers/) to a pod.


(fact
 (-> (lk/pod :foo {})
     (lk/add-init-container :bar "my-image:tag"))
 => {:apiVersion "v1"
     :kind "Pod"
     :metadata {:name :foo
                :labels {}}
     :spec {:initContainers [{:name :bar
                              :image "my-image:tag"}]}}

 ;; And with additional params...
 (-> (lk/pod :foo {})
     (lk/add-init-container :bar "my-image:tag" {:other :params}))
 => {:apiVersion "v1"
     :kind "Pod"
     :metadata {:name :foo
                :labels {}}
     :spec {:initContainers [{:name :bar
                              :image "my-image:tag"
                              :other :params}]}})

;; ### Volumes

;; The `add-volume` function takes a pod, a name, a spec for a volume
;; and a map, mapping from container names to paths, and adds the
;; volume to the pod, mounting it to the specified containers.
(fact
 (-> (lk/pod :foo {:bar :baz})
     (lk/add-container :bar "some-image")
     (lk/add-container :baz "some-other-image")
     (lk/add-volume :my-vol
                    {:configMap {:name :my-config-map}}
                    {:bar "/path/in/bar"
                     :baz "/path/in/baz"}))
 => {:apiVersion "v1"
     :kind "Pod"
     :metadata {:labels {:bar :baz}
                :name :foo}
     :spec {:containers [{:image "some-image"
                          :name :bar
                          :volumeMounts [{:name :my-vol
                                          :mountPath "/path/in/bar"}]}
                         {:image "some-other-image"
                          :name :baz
                          :volumeMounts [{:name :my-vol
                                          :mountPath "/path/in/baz"}]}]
            :volumes [{:name :my-vol
                       :configMap {:name :my-config-map}}]}})

;; A common special case for a volume is when we wish to inject files
;; into a specific container. We can do so using a config-map.

;; The `add-files-to-container` function takes a pod, a container
;; name, a unique name, a base path and a map from relative paths to
;; strings, representing the content of files. It does the following:
;; 1. Creates a config map (with the unique name).
;; 2. Adds a volume to the pod, referencing this config-map, specifying the relative paths.
;; 3. Mounts the volume to the container, at the base path.
(fact
 (-> (lk/pod :foo {:bar :baz})
     (lk/add-container :bar "some-image")
     (lk/add-container :baz "some-other-image")
     (lk/add-files-to-container :bar :unique1234 "/path/on/bar"
                                {"conf/config.conf" (lk/to-yaml {:foo :bar})
                                 "bin/script.sh" "echo hello world"}))
 => {:apiVersion "v1"
     :kind "Pod"
     :metadata {:labels {:bar :baz} :name :foo}
     :spec {:containers [{:image "some-image"
                          :name :bar
                          :volumeMounts [{:name :unique1234
                                          :mountPath "/path/on/bar"}]}
                         {:image "some-other-image"
                          :name :baz}]
            :volumes [{:name :unique1234
                       :configMap {:name :unique1234
                                   :items [{:key "c0"
                                            :path "conf/config.conf"}
                                           {:key "c1"
                                            :path "bin/script.sh"}]}}]}
     :$additional [(lk/config-map :unique1234
                                  {"c0" (lk/to-yaml {:foo :bar})
                                   "c1" "echo hello world"})]})

;; The `add-volume-claim-template` function takes a stateful-set, adds
;; a volume claim template to its spec and mounts it to the given
;; paths within the given containers.
(fact
 (-> (lk/pod :foo {:bar :baz})
     (lk/add-container :bar "some-image")
     (lk/add-container :baz "some-other-image")
     (lk/stateful-set 5 {:additional-arg 123})
     (lk/add-volume-claim-template :vol-name
                                   ;; Spec
                                   {:accessModes ["ReadWriteOnce"]
                                    :storageClassName :my-storage-class
                                    :resources {:requests {:storage "1Gi"}}}
                                   ;; Mounts
                                   {:bar "/var/lib/foo"}))
 => {:apiVersion "apps/v1"
     :kind "StatefulSet"
     :metadata {:name :foo
                :labels {:bar :baz}}
     :spec {:replicas 5
            :selector
            {:matchLabels {:bar :baz}}
            :template
            {:metadata
             {:labels {:bar :baz}}
             :spec
             {:containers
              [{:name :bar
                :image "some-image"
                :volumeMounts
                [{:name :vol-name
                  :mountPath "/var/lib/foo"}]}
               {:name :baz
                :image "some-other-image"}]}}
            :volumeClaimTemplates
            [{:metadata {:name :vol-name}
              :spec {:accessModes ["ReadWriteOnce"]
                     :storageClassName :my-storage-class
                     :resources {:requests {:storage "1Gi"}}}}]
            :additional-arg 123}})

;; If the `:volumeMounts` entry already exists in the container, the
;; new mount is appended.
(fact
 (-> (lk/pod :foo {:bar :baz})
     (lk/add-container :bar "some-image" {:volumeMounts [{:foo :bar}]})
     (lk/stateful-set 5)
     (lk/add-volume-claim-template :vol-name
                                   ;; Spec
                                   {:accessModes ["ReadWriteOnce"]
                                    :storageClassName :my-storage-class
                                    :resources {:requests {:storage "1Gi"}}}
                                   ;; Mounts
                                   {:bar "/var/lib/foo"}))
 => {:apiVersion "apps/v1"
     :kind "StatefulSet"
     :metadata {:name :foo
                :labels {:bar :baz}}
     :spec {:replicas 5
            :selector
            {:matchLabels {:bar :baz}}
            :template
            {:metadata
             {:labels {:bar :baz}}
             :spec
             {:containers
              [{:name :bar
                :image "some-image"
                :volumeMounts
                [{:foo :bar}
                 {:name :vol-name
                  :mountPath "/var/lib/foo"}]}]}}
            :volumeClaimTemplates
            [{:metadata {:name :vol-name}
              :spec {:accessModes ["ReadWriteOnce"]
                     :storageClassName :my-storage-class
                     :resources {:requests {:storage "1Gi"}}}}]}})

;; ### Annotations

;; Annotations are arbitrary key/value pairs which can appear in the
;; `:metadata` of any Kubernetes object. Annotation keys may be
;; namespaced, and we encourage the use of namespaced keywords (e.g.,
;; `::foo`) for keys.

;; The function `add-annotation` takes an API object, a key and a
;; value, and adds an annotation.
(fact
 (-> (lk/pod :foo {})
     (lk/add-annotation ::my-annotation "This is some value"))
 => {:apiVersion "v1"
     :kind "Pod"
     :metadata {:labels {}
                :name :foo
                :annotations {::my-annotation "This is some value"}}
     :spec {}})

;; ## Update Functions

;; While `add-*` functions are good for creating new API objects, we
;; sometimes need to update existing ones. For example, given a
;; deployment, we sometimes want to add an environment to one of the
;; containers in the template.

;; `update-*` work in a similar manner to Clojure's `update`
;; function. It takes an object to be augmented, an augmentation
;; function which takes the object to update as its first argument,
;; and additional arguments for that function. Then it applies the
;; augmentation function on a portion of the given object, and returns
;; the updated object.

;; `update-template` operates on controllers (deployments,
;; stateful-sets, etc). It takes a pod-modifying function and applies
;; it to the template. For example, we can use it to add a container
;; to a pod already within a deployment.
(fact
 (-> (lk/pod :foo {:bar :baz})
     (lk/deployment 3)
     ;; The original pod has no containers. We add one now.
     (lk/update-template lk/add-container :bar "some-image"))
 => {:apiVersion "apps/v1"
     :kind "Deployment"
     :metadata {:name :foo
                :labels {:bar :baz}}
     :spec {:replicas 3
            :selector
            {:matchLabels {:bar :baz}}
            :template
            {:metadata
             {:labels {:bar :baz}}
             :spec
             {:containers
              [{:name :bar
                :image "some-image"}]}}}})

;; `update-container` works on a pod. It takes a container name, and
;; applies the augmentation function with its arguments on the
;; container with the given name. It can be used in conjunction with
;; `update-template` to operate on a controller.
(fact
 (-> (lk/pod :foo {:bar :baz})
     (lk/add-container :bar "some-image")
     (lk/add-container :baz "some-other-image")
     (lk/deployment 3)
     ;; We add an environment to a container.
     (lk/update-template lk/update-container :bar lk/add-env {:FOO "BAR"}))
 => {:apiVersion "apps/v1"
     :kind "Deployment"
     :metadata {:name :foo
                :labels {:bar :baz}}
     :spec {:replicas 3
            :selector
            {:matchLabels {:bar :baz}}
            :template
            {:metadata
             {:labels {:bar :baz}}
             :spec
             {:containers
              [{:name :bar
                :image "some-image"
                :env [{:name :FOO
                       :value "BAR"}]}
               {:name :baz
                :image "some-other-image"}]}}}})

;; `update-container` works on init containers as well as regular
;; containers.
(fact
 (-> (lk/pod :foo {:bar :baz})
     (lk/add-container :bar "some-image")
     (lk/add-init-container :baz "some-other-image")
     (lk/deployment 3)
     ;; We add an environment to a container.
     (lk/update-template lk/update-container :baz lk/add-env {:FOO "BAZ"}))
 => {:apiVersion "apps/v1"
     :kind "Deployment"
     :metadata {:name :foo
                :labels {:bar :baz}}
     :spec {:replicas 3
            :selector
            {:matchLabels {:bar :baz}}
            :template
            {:metadata
             {:labels {:bar :baz}}
             :spec
             {:containers
              [{:name :bar
                :image "some-image"}]
              :initContainers
              [{:name :baz
                :image "some-other-image"
                :env [{:name :FOO
                       :value "BAZ"}]}]}}}})

;; # Exposure Functions

;; In Kubernetes, to make a network interface in one pod available
;; outside that pod, two things need to be defined. First, the
;; necessary ports need to be exported from the relevant
;; container(s). Second, a service must be defined, forwarding these
;; ports to either a virtual IP address (in the case of a `:ClusterIP`
;; service, or a port of the hosting node, as in the case of a
;; `:NodePort` service.

;; Lambda-Kube takes a two-step approach to allow the exposure of
;; network interfaces. The first step involves the `port`
;; function. This function takes a name of a container, a name for the
;; port, a port number on that container and (optionally) a port
;; number to be exported. It returns a function that transforms both a
;; pod and a service.
(fact
 (let [p (lk/port :my-cont :web 80 8080)
       ;; Based on the kind of service, we provide a function that
       ;; updates the service with the new ports.
       edit-svc (fn [svc podname podport svcport]
                  (update svc :spec lk/field-conj :ports
                          {:port svcport :targetPort podport :name podname}))
       pod (-> (lk/pod :my-pod {})
               (lk/add-container :my-cont "some-image"))
       svc {:metadata {:name :foo}
            :spec {}}
       [pod svc] (p [pod svc edit-svc])]
   pod => (-> (lk/pod :my-pod {})
              (lk/add-container :my-cont "some-image" {:ports [{:containerPort 80
                                                                :name :web}]}))
   svc => {:metadata {:name :foo}
           :spec {:ports [{:port 8080
                           :targetPort 80
                           :name :web}]}}))

;; `port` is composable through functional composition (`comp`).
(fact
 (let [p (comp (lk/port :my-cont :web 80 8080)
               (lk/port :my-cont :https 443 443))
       edit-svc (fn [svc portname podport svcport]
                  (update svc :spec lk/field-conj :ports
                          {:port svcport :targetPort podport :name portname}))
       pod (-> (lk/pod :my-pod {})
               (lk/add-container :my-cont "some-image"))
       svc {:metadata {:name :foo}
            :spec {}}
       [pod svc] (p [pod svc edit-svc])]
   pod => (-> (lk/pod :my-pod {})
              (lk/add-container :my-cont "some-image" {:ports [{:containerPort 443
                                                                :name :https}
                                                               {:containerPort 80
                                                                :name :web}]}))
   svc => {:metadata {:name :foo}
           :spec {:ports [{:port 443
                           :targetPort 443
                           :name :https}
                          {:port 8080
                           :targetPort 80
                           :name :web}]}}))

;; The second step involves a family of `expose*` functions, which
;; create different kinds of services.

;; The basic `expose` function takes a deployment-like API object
;; (deployment, stateful-set, job), a name, a function like the one
;; returned from `port`, a map with additional properties and a
;; function for editing the service, adding a port mapping.

;; It returns the deployment-like object augmented such that:
;; 1. A new service object is added in an `:$additional` field.
;; 2. The `:template` is augmented according to the `port` function(s).
(fact
 (-> (lk/pod :foo {:bar :baz})
     (lk/add-container :quux "some-image")
     (lk/deployment 3)
     (lk/expose :foo-srv
                (lk/port :quux :web 80 30080)
                {:type :NodePort}
                (fn [svc portname podport svcport]
                  (update svc :spec lk/field-conj :ports
                          {:port podport :nodePort svcport}))))
 => {:apiVersion "apps/v1"
     :kind "Deployment"
     :metadata {:name :foo
                :labels {:bar :baz}}
     :spec {:replicas 3
            :selector {:matchLabels {:bar :baz}}
            :template {:metadata {:labels {:bar :baz}}
                       :spec {:containers [{:name :quux
                                            :image "some-image"
                                            :ports [{:containerPort 80
                                                     :name :web}]}]}}}
     :$additional [{:apiVersion "v1"
                    :kind "Service"
                    :metadata {:name :foo-srv}
                    :spec {:type :NodePort
                           :selector {:bar :baz}
                           :ports [{:port 80
                                    :nodePort 30080}]}}]})

;; If used on a stateful-set, `expose` also adds a `:serviceName`
;; field to its `:spec`, specifying the service name.
(fact
 (-> (lk/pod :foo {:bar :baz})
     (lk/add-container :quux "some-image")
     (lk/stateful-set 3)
     (lk/expose :foo-srv
                (lk/port :quux :web 80 30080)
                {:type :NodePort}
                (fn [svc portname podport svcport]
                  (update svc :spec lk/field-conj :ports
                          {:port podport :nodePort svcport}))))
 => {:apiVersion "apps/v1"
     :kind "StatefulSet"
     :metadata {:name :foo
                :labels {:bar :baz}}
     :spec {:replicas 3
            :selector {:matchLabels {:bar :baz}}
            :template {:metadata {:labels {:bar :baz}}
                       :spec {:containers [{:name :quux
                                            :image "some-image"
                                            :ports [{:containerPort 80
                                                     :name :web}]}]}}
            :volumeClaimTemplates []
            :serviceName :foo-srv}
     :$additional [{:apiVersion "v1"
                    :kind "Service"
                    :metadata {:name :foo-srv}
                    :spec {:type :NodePort
                           :selector {:bar :baz}
                           :ports [{:port 80
                                    :nodePort 30080}]}}]})

;; The `expose` function is not intended to be used directly. Instead,
;; `expose-*` functions cover the different service types.

;; ## ClusterIP Services

;; To create ClusterIP services, use the `expose-cluster-ip`
;; function. It takes a deployment, a name, and a port function, and
;; returns a ClusterIP service.
(fact
 (-> (lk/pod :foo {:bar :baz})
     (lk/add-container :quux "some-image")
     (lk/deployment 3)
     (lk/expose-cluster-ip :foo-srv
                           (lk/port :quux :web 80 8080)))
 => {:apiVersion "apps/v1"
     :kind "Deployment"
     :metadata {:name :foo
                :labels {:bar :baz}}
     :spec {:replicas 3
            :selector {:matchLabels {:bar :baz}}
            :template {:metadata {:labels {:bar :baz}}
                       :spec {:containers [{:name :quux
                                            :image "some-image"
                                            :ports [{:containerPort 80
                                                     :name :web}]}]}}}
     :$additional [{:apiVersion "v1"
                    :kind "Service"
                    :metadata {:name :foo-srv}
                    :spec {:type :ClusterIP
                           :selector {:bar :baz}
                           :ports [{:port 8080
                                    :targetPort :web
                                    :name :web}]}}]})

;; ## Headless Services

;; `expose-headless` creates a `:ClusterIP` service, but sets
;; `:clusterIP` to be `:None`.
(fact
 (-> (lk/pod :foo {:bar :baz})
     (lk/add-container :quux "some-image")
     (lk/stateful-set 3)
     (lk/expose-headless :foo-srv
                         (lk/port :quux :web 80 8080)))
 => {:apiVersion "apps/v1"
     :kind "StatefulSet"
     :metadata {:name :foo
                :labels {:bar :baz}}
     :spec {:replicas 3
            :selector {:matchLabels {:bar :baz}}
            :template {:metadata {:labels {:bar :baz}}
                       :spec {:containers [{:name :quux
                                            :image "some-image"
                                            :ports [{:containerPort 80
                                                     :name :web}]}]}}
            :volumeClaimTemplates []
            :serviceName :foo-srv}
     :$additional [{:apiVersion "v1"
                    :kind "Service"
                    :metadata {:name :foo-srv}
                    :spec {:type :ClusterIP
                           :clusterIP :None
                           :selector {:bar :baz}
                           :ports [{:port 8080
                                    :name :web
                                    :targetPort :web}]}}]})

;; ## NodePort Services

;; `expose-node-port` creates a service of type `:NodePort`.
(fact
 (-> (lk/pod :foo {:bar :baz})
     (lk/add-container :quux "some-image")
     (lk/deployment 3)
     (lk/expose-node-port :foo-srv
                          (lk/port :quux :web 80 30080)))
 => {:apiVersion "apps/v1"
     :kind "Deployment"
     :metadata {:name :foo
                :labels {:bar :baz}}
     :spec {:replicas 3
            :selector {:matchLabels {:bar :baz}}
            :template {:metadata {:labels {:bar :baz}}
                       :spec {:containers [{:name :quux
                                            :image "some-image"
                                            :ports [{:containerPort 80
                                                     :name :web}]}]}}}
     :$additional [{:apiVersion "v1"
                    :kind "Service"
                    :metadata {:name :foo-srv}
                    :spec {:type :NodePort
                           :selector {:bar :baz}
                           :ports [{:targetPort :web
                                    :name :web
                                    :port 80
                                    :nodePort 30080}]}}]})

;; If the target port is omitted, a `:nodePort` is not specified in the
;; service.
(fact
 (-> (lk/pod :foo {:bar :baz})
     (lk/add-container :quux "some-image")
     (lk/deployment 3)
     (lk/expose-node-port :foo-srv
                          (lk/port :quux :web 80)))
 => {:apiVersion "apps/v1"
     :kind "Deployment"
     :metadata {:name :foo
                :labels {:bar :baz}}
     :spec {:replicas 3
            :selector {:matchLabels {:bar :baz}}
            :template {:metadata {:labels {:bar :baz}}
                       :spec {:containers [{:name :quux
                                            :image "some-image"
                                            :ports [{:containerPort 80
                                                     :name :web}]}]}}}
     :$additional [{:apiVersion "v1"
                    :kind "Service"
                    :metadata {:name :foo-srv}
                    :spec {:type :NodePort
                           :selector {:bar :baz}
                           :ports [{:targetPort :web
                                    :name :web
                                    :port 80}]}}]})

;; # Dependency Injection

;; Functions such as `pod` and `deployment` help build Kubernetes API
;; objects. If we consider Lambda-Kube to be a language, these are the
;; _common nouns_. They can be used to build general Pods,
;; Deployments, StatefulSets, etc, and can be used to develop other
;; functions that create general things such as a generic Redis
;; database, or a generic Nginx deployment, which can also be
;; represented as a function.

;; However, when we go down to the task of defining a system, we need
;; a way to define _proper nouns_, such as _our_ Redis database and
;; _our_ Nginx deployment.

;; This distinction is important because when creating a generic Nginx
;; deployment, it stands on its own, and is unrelated to any Redis
;; database that may or may not be used in conjunction with
;; it. However, when we build our application, which happens to have
;; some, e.g., PHP code running on top of Nginx, which happens to
;; require a database, this is when we need the two to be
;; connected. We need to connect them by, e.g., adding environment
;; variables to the Nginx container, so that PHP code that runs over
;; it will be able to connect to the database.

;; This is where dependency injection comes in. Dependency Injection
;; (DI) is a general concept that allows developers to define proper
;; nouns in their software in an incremental way. It starts with some
;; configuration, which provides arbitrary settings. Then a set of
;; resources is being defined. Each such resource may depend on other
;; resources, including configuration.

;; Our implementation of DI, resources are identified with symbols,
;; corresponding to the proper nouns. These nouns are defined in
;; functions, named _modules_, which take a single parameter -- an
;; _injector_ (marked as `$` by convention), and augment it by adding
;; new rules to it.
(defn module1 [$]
  (-> $
      (lk/rule :my-deployment []
               (fn []
                 (-> (lk/pod :my-pod {:app :my-app})
                     (lk/deployment 3))))))

;; This module uses the `rule` function to define a single _rule_. A
;; rule has a _name_, a vector of _dependencies_, and a function that
;; takes the dependency values and returns an API object. In this
;; case, the name is `:my-deployment`, there are no dependencies, and
;; the API object is a deployment of three pods.

;; The `injector` function creates a fresh injector. This injector can
;; be passed to the module to add the rules it defines. Then the
;; function `get-deployable` to get all the API objects in the system,
;; according to the given configuration.
(fact
 (-> (lk/injector)
     (module1)
     (lk/get-deployable {}))
 => [(-> (lk/pod :my-pod {:app :my-app})
         (lk/deployment 3))])

;; Rules may depend on configuration parameters. These parameters need
;; to be listed as dependencies, and then, if they exist in the
;; injector's configuration, their values are passed to the
;; function. In the following example, the module has two rules:
;; `:my-deployment`, and `:not-going-to-work`. The former is similar
;; to the one defined in `module1`, but takes the number of replicas
;; from the configuration. The latter depends on the parameter
;; `:does-not-exist`.
(defn module2 [$]
  (-> $
      (lk/rule :not-going-to-work [:does-not-exist]
               (fn [does-not-exist]
                 (lk/pod :no-pod {:app :no-app})))
      (lk/rule :my-deployment [:my-deployment-num-replicas]
               (fn [num-replicas]
                 (-> (lk/pod :my-pod {:app :my-app})
                     (lk/deployment num-replicas))))))

;; Now, if we provide a configuration that only contains
;; `:my-deployment-num-replicas`, but not `:not-going-to-work`,
;; `:my-deployment` will be created, but not `:not-going-to-work`.
(fact
 (-> (lk/injector)
     (module2)
     (lk/get-deployable {:my-deployment-num-replicas 5}))
 => [(-> (lk/pod :my-pod {:app :my-app})
         (lk/deployment 5))])

;; When an API object contains nested objects (a `:$additional`
;; attribute), the nested objects are recursively extracted, and added
;; to the returned list.
(defn module3 [$]
  (-> $
      (lk/rule :my-service [:my-deployment-num-replicas]
               (fn [num-replicas]
                 (-> (lk/pod :my-service {:app :my-app})
                     (lk/add-container :my-cont "some-image")
                     (lk/deployment num-replicas)
                     (lk/expose-cluster-ip :my-service (lk/port :my-cont :web 80 80)))))))

(fact
 (-> (lk/injector)
     (module3)
     (lk/get-deployable {:my-deployment-num-replicas 5}))
 => [(-> (lk/pod :my-service {:app :my-app})
         (lk/add-container :my-cont "some-image"
                           {:ports [{:containerPort 80
                                     :name :web}]})
         (lk/deployment 5))
     {:apiVersion "v1"
      :kind "Service"
      :metadata {:name :my-service}
      :spec {:ports [{:port 80
                      :targetPort :web
                      :name :web}]
             :selector {:app :my-app}
             :type :ClusterIP}}])

;; Resources may depend on one another. The following module depends
;; on `:my-service`.
(defn module4 [$]
  (-> $
      (lk/rule :my-pod [:my-service]
               (fn [my-service]
                 (lk/pod :my-pod {:app :my-app})))))

(fact
 (-> (lk/injector)
     (module4)
     (module3)
     (lk/get-deployable {:my-deployment-num-replicas 5}))
 => [(-> (lk/pod :my-service {:app :my-app})
         (lk/add-container :my-cont "some-image"
                           {:ports [{:containerPort 80
                                     :name :web}]})
         (lk/deployment 5))
     {:apiVersion "v1"
      :kind "Service"
      :metadata {:name :my-service}
      :spec {:ports [{:port 80
                      :targetPort :web
                      :name :web}]
             :selector {:app :my-app}
             :type :ClusterIP}}
     (lk/pod :my-pod {:app :my-app})])

;; Rules can compete with each other. For example, two rules can
;; define the resource `:foo`, and give it two different
;; meanings.
(defn module5 [$]
  (-> $
      (lk/rule :foo [:use-bar]
               (fn [use-bar]
                 (lk/pod :bar {})))
      (lk/rule :foo [:use-baz]
               (fn [use-baz]
                 (lk/pod :baz {})))))

;; Assuming the requierements for only one of these rules is met, this
;; rule will take effect.
(fact
 (-> (lk/injector)
     (module5)
     (lk/get-deployable {:use-bar true}))
 => [(lk/pod :bar {})])

;; If two or more competing rules can be applied, an exception is
;; thrown.
(fact
 (-> (lk/injector)
     (module5)
     (lk/get-deployable {:use-bar true
                         :use-baz true}))
 => (throws "Conflicting prerequisites for resource :foo"))

;; ## Describers and Descriptions

;; When one resource depends on another, it often needs information
;; about the other in order to perform its job properly. For example,
;; if the dependency is a service, the resource depending on this
;; service may need the host name and port number of that service.

;; One option would be to provide the complete API object as the
;; dependency information. However, that would defeat the purpose of
;; using DI. The whole idea behind using DI is a decoupling between a
;; resource and its dependencies. If we provide the API object to the
;; rule function, we force it to know what its dependency is, and how
;; to find information there.

;; But almost any problem in computer science can be solved by adding
;; another level of indirection (the only one that isn't is the
;; problem of having too many levels of indirection). In our case, the
;; extra level of indirection is provided by _describers_.

;; Describers are functions that examine an API object, and extract
;; _descriptions_. A description is a map, containing information
;; about the object. Describers are defined inside modules, using the
;; `desc` functions. All describers are applied to all objects. If a
;; describer is not relevant to a certain object it may return
;; `nil`. If it is, it should return a map with some fields
;; representing the object.

;; For example, the following module defines three describers. The
;; first extracts the name out of any object. The second returns the
;; port number for a service (or `nil` if not), and the third extracts
;; the labels.
(defn module6 [$]
  (-> $
      (lk/desc (fn [obj]
                 (when (contains? #{"Pod" "Deployment"} (:kind obj))
                   {:name (-> obj :metadata :name)})))
      (lk/desc (fn [obj]
                 (when (= (:kind obj) "Service")
                   {:hostname (-> obj :metadata :name)})))
      (lk/desc (fn [obj]
                 (when (contains? #{"Pod" "Deployment"} (:kind obj))
                   {:labels (-> obj :metadata :labels)})))
      (lk/rule :dependency [:use-simple-pod]
               (fn [use-simple-pod]
                 (lk/pod :my-first-pod {})))
      (lk/rule :dependent [:dependency]
               (fn [dep]
                 (lk/pod :my-second-pod {:the-name (:name dep)
                                         :the-hostname (:hostname dep)
                                         :the-labels (:labels dep)})))))

;; The module also defines two rules for two pods. The second pod
;; depends on the first one, and populates its labels with information
;; about the first pod (not a real-life scenario). When we call
;; `get-deployable`, we will get both pods. The labels in the second
;; pod will be set so that the name will be there, but not the port.
(fact
 (-> (lk/injector)
     (module6)
     (lk/get-deployable {:use-simple-pod true}))
 => [(lk/pod :my-first-pod {})
     (lk/pod :my-second-pod {:the-name :my-first-pod
                             :the-hostname nil
                             :the-labels {}})])

;; When an API object contains nested objects (`:$additional` fields),
;; describer functions are applied to all nested objects.

;; Consider for example an alternative rule that defines the above
;; `:dependency`, and this time, uses `expose-cluster-ip` to attach a
;; service.
(defn module7 [$]
  (-> $
      (lk/rule :dependency [:use-depl-with-svc]
               (fn [use-depl-with-svc]
                 (-> (lk/pod :my-depl {})
                     (lk/add-container :foo "some-image")
                     (lk/deployment 3)
                     (lk/expose-cluster-ip :my-svc (lk/port :foo :web 80 80)))))))

;; Now, if we use this module in conjunction with `module6`, and
;; provide the configuration parameter that triggers our new
;; definition, the `:dependent` pod should see the `:hostname`
;; parameter contributed by the nested service.
(fact
 (-> (lk/injector)
     (module6)
     (module7)
     (lk/get-deployable {:use-depl-with-svc true})
     (last))
 => (lk/pod :my-second-pod {:the-name :my-depl
                            :the-hostname :my-svc
                            :the-labels {}}))

;; ## Standard Describers

;; While users are free to define their own describers, Lambda-Kube
;; provides a `standard-descs` module, containing some standard
;; describers.

;; Consider the following module, defining a deployment, exposed as a
;; service, with some annotations, and a pod that depends on it.
(defn module8 [$]
  (-> $
      (lk/rule :my-service [:my-deployment-num-replicas]
               (fn [num-replicas]
                 (-> (lk/pod :my-deployment {:foo :bar})
                     (lk/add-container :quux "some-image")
                     (lk/deployment num-replicas)
                     (lk/add-annotation ::key "val")
                     (lk/expose-cluster-ip :my-service
                                           (lk/port :quux :web 80 80)))))
      (lk/rule :my-pod [:my-service]
               (fn [my-service]
                 (-> (lk/pod :my-pod {;; The :annotations attribute contains the object's annotations,
                                      ;; if they are defined.
                                      :x (-> my-service ::key)})
                     (lk/add-container :baz "some-image"
                                       (lk/add-env {} {;; The :hostname attribute holds the name of the service
                                                       :MY_SVC_HOSTNAME (:hostname my-service)
                                                       ;; The :ports attribute is a map, mapping from
                                                       ;; port names to service port numbers.
                                                       :MY_SVC_WEB_PORT (-> my-service :ports :web)})))))))

;; Now, when we use this module in conjunction with the
;; `standard-descs` module, the information about the dependency
;; (`:my-service` is provided to the dependent (`:my-pod`).
(fact
 (-> (lk/injector)
     (module8)
     (lk/standard-descs)
     (lk/get-deployable {:my-deployment-num-replicas 3})
     (last))
 => (-> (lk/pod :my-pod {:x "val"})
        (lk/add-container :baz "some-image"
                          (lk/add-env {} {:MY_SVC_HOSTNAME "my-service"
                                          :MY_SVC_WEB_PORT 80}))))

;; # Augmentation Rules

;; While dependency injection is a powerful tool for defining a
;; system's functionality while decoupling between one component to
;; another, it is not very good for defining the non-functional
;; properties of a system, as they require a global view of the entire
;; system.

;; Consider for example resource limits and requests on
;; containers. The values set there may vary significatly depending on
;; the context. For functional tests we would require much less
;; resources than, e.g., for production use.

;; We could, for example, pass the amount of requested resources for
;; the different kinds of containers as configuration parameters, and
;; have the DI rules use them, but that would end up with a lot of
;; code duplication, as resource specifications are required for each
;; and every container.

;; This problem is similar to the one that
;; [CSS](https://en.wikipedia.org/wiki/Cascading_Style_Sheets) is
;; intended to solve. While HTML defines the structure of a web-page,
;; specifying the styling of each and every component in the HTML
;; content would lead to a lot of duplication, as many elements share
;; the same stying. CSS solves this problem by providing style
;; attributes to HTML elements based on their basic properties (e.g.,
;; type, id and class). In addition to removing much of the
;; duplication, this also helps with decoupling, as different CSS
;; stylesheets can be used in different cases, to style the same
;; content.

;; We take our inspiration from CSS, as we build our solution for
;; non-functional specifications. As with CSS, our solution is made of
;; declarative rules that apply to the structure of the deployment as
;; constructed by the DI mechanism. As with CSS, each rule contains a
;; _matcher_, determining on which node it needs to be applied, and an
;; _update_ to be made to each node matching the selector.

;; ## Usage

;; `get-deployable` takes an optional extra argument, consisting of a
;; list of _rules_, each being a tuple (vector) consisting of a
;; [matcher](#matchers) and an [updater](#updaters). These rules are
;; applied, in order, to each of the API objects generated by
;; `get-deployable`, and also to some of their parts (see
;; [Walkers](#walkers).

;; Consider for example `:my-deployment` and `:my-pod` from `module8`
;; above. As they are defined, they do not specify resources, as the
;; amount of resources they need depends heavily on the way they are
;; being used (e.g., production vs. testing). Therefore, we will
;; assign these using rules. First, we define a function that takes an
;; expected CPU usage and an expected memory usage (in megabytes), and
;; creates a `:resources` map that sets the given numbers as
;; `:requests` (the requested amount), and sets a `:limits` map,
;; limiting the container to five times the requested CPU usage, and
;; 1.5 times the requested memory usage.
(defn resources [cpu mem]
  {:resources {:requests {:cpu cpu
                          :memory (int (* mem 1024 1024))}
               :limits {:cpu (* cpu 5)
                        :memory (int (* mem 1024 1024 1.5))}}})

;; We define two rules. The first sets baseline resources for all
;; containers, and the second sets special values for
;; `:my-deployment`.
(def rules
  [[{:kind :Container} (resources 0.1 20)]
   [{:kind :Container
     :metadata {:name :my-deployment}} (resources 0.5 400)]])

;; Now, when calling `get-deployable` with the rules as its last
;; argument, the result is agumented with the resources.
(fact
 (-> (lk/injector)
     (module8)
     (lk/standard-descs)
     (lk/get-deployable {:my-deployment-num-replicas 3}
                        rules))
 => [(-> (lk/pod :my-deployment {:foo :bar})
         (lk/add-container :quux "some-image"
                           (-> {:ports [{:containerPort 80
                                         :name :web}]}
                               (merge (resources 0.5 400))))
         (lk/deployment 3)
         (lk/add-annotation ::key "val"))
     {:kind "Service"
      :apiVersion "v1"
      :metadata {:name :my-service}
      :spec {:selector {:foo :bar}
             :type :ClusterIP
             :ports [{:port 80
                      :name :web
                      :targetPort :web}]}}
     (-> (lk/pod :my-pod {:x "val"})
         (lk/add-container :baz "some-image"
                           (-> {}
                               (lk/add-env {:MY_SVC_HOSTNAME "my-service"
                                            :MY_SVC_WEB_PORT 80})
                               (merge (resources 0.1 20)))))])

;; ## Matchers

;; Matchers boil down to Boolean functions, which take two parameter:
;; a node to match against, and a map of contextual information. The
;; `matcher` function takes a matcher as parameter, and returns such a
;; matcher function.

;; Functions of arity 2 are taken verbatim.
(fact
 (let [f (fn [node ctx]
           (= node 3))
       m (lk/matcher f)]
   (m 2 {}) => false
   (m 3 {}) => true))

;; Functions of arity 1 are converted to arity 2 (second argument is
;; ignored).
(fact
 (let [m (lk/matcher #(= % 3))]
   (m 2 {}) => false
   (m 3 {}) => true))

;; Other arities are illegal
(fact
 (lk/matcher (fn [])) => (throws "Invalid matcher arity: 0"))

;; A scalar value matches its value
(fact
 (let [m (lk/matcher 3)]
   (m 2 {}) => false
   (m 3 {}) => true))

;; A matcher can be a map, in which case all values are considered as
;; matchers. For a map matcher to match a node, either the node or the
;; context must have fields that satisfy each field of the matcher.
(fact
 (let [m (lk/matcher {:foo 3
                      :bar #(> % 4)})]
   (m {:foo 3
       :bar 5} {}) => true
   (m {:foo 3
       :bar 3} {}) => false
   (m {:foo 3} {}) => false
   (m {:foo 3} {:bar 5}) => true))

;; If a key exists in both the node and the context, the node wins.
(fact
 (let [m (lk/matcher {:foo 3
                      :bar #(> % 4)})]
   (m {:foo 3
       :bar 5}
      {:bar 3}) => true))

;; Map matchers can be nested. Nesting works on both the node and the
;; context in parallel.
(fact
 (let [m (lk/matcher {:a {:b {:c 1
                              :d 2}}})]
   (m {:a {:b {:c 1}}}
      {:a {:b {:d 2}}}) => true))

;; Matchers can also be sets. The elements of the set are treated as
;; matchers, and need to all match for the set to match.
(fact
 (let [m (lk/matcher #{vector?
                       #(> (count %) 2)})]
   (m {:foo 1
       :bar 2
       :baz 3} {}) => false
   (m [1 2] {}) => false
   (m [1 2 3] {}) => true))

;; Keywords have different meaning for different node types. For maps,
;; they match nodes that contain the key specified by the matcher,
;; regardless of whether this key appears in the context or not.
(fact
 (let [m (lk/matcher :foo)]
   (m {:foo 1} {}) => true
   (m {} {:foo 1}) => false))

;; A keyword also matches itself (a keyword with the same name), and
;; strings representing that name.
(fact
 (let [m (lk/matcher :foo)]
   (m :foo {}) => true
   (m :bar {}) => false
   (m "foo" {}) => true
   (m "bar" {}) => false))


;; Regular expressions match strings that, well, match them...


(fact
 (let [m (lk/matcher #"hello.*")]
   (m {} {}) => false
   (m "hello, world" {}) => true
   (m "world, hello" {}) => false))

;; ## Updaters

;; An updater represents a function from a node to an updated version
;; of itself. The `updater` function takes an updater and returns an
;; equivalent function.

;; Functions are taken as updaters.
(fact
 (let [u (lk/updater inc)]
   (u 1) => 2))

;; Scalars update any value to themselves.
(fact
 (let [u (lk/updater 3)]
   (u "foo") => 3))

;; Vectors update the given value by applying its elements one by one,
;; in order.
(fact
 (let [u (lk/updater [#(str % " hello")
                      #(str % " world")])]
   (u "foo") => "foo hello world"))

;; A map applies updaters to the respective fields of the node.
(fact
 (let [u (lk/updater {:foo [inc
                            #(* % 2)]
                      :bar dec})]
   (u {:foo 1 :bar 1}) => {:foo 4 :bar 0}))


;; # Interacting with Kubernetes

;; All the above functions are pure functions that help build
;; Kubernetes API objects for systems. The following functions help
;; translate these objects into a real update of the state of a
;; Kubernetes cluster.

;; `to-yaml` takes a vector of API objects and returns a YAML string
;; acceptable by Kubernetes.


(fact
 (-> (lk/pod :nginx-deployment {:app :nginx})
     (lk/add-container :nginx "nginx:1.7.9")
     (lk/deployment 3)
     (lk/expose-cluster-ip :nginx-svc (lk/port :nginx :web 80 80))
     (lk/extract-additional)
     ((fn [x] (cons x (-> x meta :additional))))
     (lk/to-yaml)) =>
 "apiVersion: apps/v1
kind: Deployment
metadata:
  name: nginx-deployment
  labels:
    app: nginx
spec:
  replicas: 3
  selector:
    matchLabels:
      app: nginx
  template:
    metadata:
      labels:
        app: nginx
    spec:
      containers:
      - name: nginx
        image: nginx:1.7.9
        ports:
        - containerPort: 80
          name: web
---
kind: Service
apiVersion: v1
metadata:
  name: nginx-svc
spec:
  type: ClusterIP
  selector:
    app: nginx
  ports:
  - port: 80
    name: web
    targetPort: web
")

;; `kube-apply` takes a string constructed by `to-yaml` and a `.yaml`
;; file. If the file does not exist, it creates it, and calls `kubectl
;; apply` on it.
(fact
 (let [f (io/file "foo.yaml")]
   (when (.exists f)
     (.delete f))
   (lk/kube-apply "foo: bar" f) => irrelevant
   (provided
    (sh/sh "kubectl" "apply" "-f" "foo.yaml") => {:exit 0})
   (.exists f) => true
   (slurp f) => "foo: bar"))

;; If the file already exists, and has the exact same content as the
;; given string, nothing happens.
(fact
 (let [f (io/file "foo.yaml")]
   (lk/kube-apply "foo: bar" f) => irrelevant
   (provided
    ;; Not called
    (sh/sh "kubectl" "apply" "-f" "foo.yaml") => {:exit 0} :times 0)))

;; If the file exists, but the new content is different than what was
;; stored in that file, the file is updated and `kubectl apply` is
;; called.
(fact
 (let [f (io/file "foo.yaml")]
   (lk/kube-apply "foo: baz" f) => irrelevant
   (provided
    (sh/sh "kubectl" "apply" "-f" "foo.yaml") => {:exit 0})
   (.exists f) => true
   (slurp f) => "foo: baz"))

;; If `kubectl` fails (returns a non-zero exit status), an exception
;; is thrown with the content of the standard error, and the file is
;; deleted, to make sure it is applied next time.
(fact
 (let [f (io/file "foo.yaml")]
   (when (.exists f)
     (.delete f))
   (lk/kube-apply "foo: bar" f) => (throws "there was a problem with foo")
   (provided
    (sh/sh "kubectl" "apply" "-f" "foo.yaml")
    => {:exit 33
        :err "there was a problem with foo"})
   (.exists f) => false))


;; # Under the Hood

;; ## Flattening Nested API Objects

;; API objects constructed in lambda-kube can have a `:$additional`
;; field anywhere in their structure, containing a vector of
;; additional API objects. The `extract-additional` function takes an
;; API object (as a Clojure map), and returns the same object with all
;; nested `:$additional` fields removed, and a meta-field --
;; `:additional`, containin a list of all nested objects.

;; For a map that does not contain `:$additional`, the map is returned
;; as-is, and the `:additional` meta-field is empty.


(fact
 (let [ext (lk/extract-additional {:foo :bar})]
   ext => {:foo :bar}
   (-> ext meta :additional) => empty?))

;; For a map containing `:$additional`, the underlying objects are
;; placed in the `:additional` meta-field, and the field itself is
;; removed from the map.
(fact
 (let [ext (lk/extract-additional {:foo :bar
                                   :$additional [{:x 1}
                                                 {:y 2}]})]
   ext => {:foo :bar}
   (-> ext meta :additional) => [{:x 1}
                                 {:y 2}]))

;; If the nested maps contain `:$additional`, their respective content
;; is also added to the `:additional` meta-field.
(fact
 (let [ext (lk/extract-additional {:foo :bar
                                   :$additional [{:x 1
                                                  :$additional [{:z 3}]}
                                                 {:y 2}]})]
   ext => {:foo :bar}
   (set (-> ext meta :additional)) => #{{:x 1}
                                        {:y 2}
                                        {:z 3}}))

;; `:$additional` fields can appear anywhere in the structure, even
;; inside another `:$additional` field.
(fact
 (let [ext (lk/extract-additional {:foo :bar
                                   :baz {:x 1
                                         :$additional [{:z 3}]}
                                   :quux [{:p 1
                                           :$additional [{:y 2
                                                          :$additional [{:w 3}]}]}]})]
   ext => {:foo :bar
           :baz {:x 1}
           :quux [{:p 1}]}
   (-> ext meta :additional) => [{:z 3}
                                 {:y 2}
                                 {:w 3}]))

;; ## Augmentation Rules

;; A matcher and an updater are combined to a single function using
;; the `aug-rule` function. An augmentation rule function takes a node
;; and a context and applies the matcher to them. If it matches, the
;; updater is applied to the node. If not, the node is returned
;; unchanged.
(fact
 (let [r (lk/aug-rule {:foo #(> % 2)} {:bar inc})]
   (r {:foo 1 :bar 1} {}) => {:foo 1 :bar 1}
   (r {:foo 3 :bar 1} {}) => {:foo 3 :bar 2}
   (r {:bar 1} {:foo 3}) => {:bar 2}))

;; The function `aug-rule-comp` composes a collection of rule
;; functions into a single rule function, which applies these rules in
;; order (this works opposite to Clojure's `comp` function, which
;; applies the functions in reverse order).
(fact
 (let [rules [(lk/aug-rule {:foo #(> % 2)} {:bar inc})
              (lk/aug-rule {:foo #(<= % 2)} {:bar dec})
              (lk/aug-rule {:foo 1} {:bar 7})]
       rule (lk/aug-rule-comp rules)]
   (rule {:bar 2} {:foo 3}) => {:bar 3}
   ;; The last rule takes precedence over its predecessors
   (rule {:bar 2} {:foo 1}) => {:bar 7}))

;; As a shorthand, the function `aug-rules` takes a list (or vector)
;; of pairs (vectors of size 2), each consisting of a matcher and an
;; updater, and returns a combined rule.
(fact
 (let [rule (lk/aug-rules [[{:foo #(> % 2)} {:bar inc}]
                           [{:foo #(<= % 2)} {:bar dec}]
                           [{:foo 1} {:bar 7}]])]
   (rule {:bar 2} {:foo 3}) => {:bar 3}
   ;; The last rule takes precedence over its predecessors
   (rule {:bar 2} {:foo 1}) => {:bar 7}))

;; ## Walkers

;; We wish to apply rules to top-level API objects, as well as to
;; sub-objects, such as templates of deployments and stateful-sets,
;; and containers of nodes.

;; Walkers are used to traverse an API object, applying the given rule
;; (which can be a composition of rules) to zero or more parts of a
;; node.

;; Walkers are registered under the `:walkers` key of the
;; injector. Given an injector with a list of walkers, a rule function
;; and an API object, the `apply-aug-rule` function applies the rule
;; everywhere the walkers take it.

;; In the following example, we compose three rules: one that matches
;; deployments, one that matches pods and one that matches
;; containers. We apply two walkers: one that applies the rule to a
;; template, if exists, and one that applies the rule to each
;; container, if exists. The first walker adds a `:foo` key to the
;; context, which both the pod and container rules expect to see. We
;; expect to see all three kinds of objects: deployment, pod and
;; containers, marked accordingly.
(fact
 (let [rule (lk/aug-rules [[{:kind "Deployment"} {:comment "This is a deployment"}]
                           [{:kind "Pod"
                             :foo true} {:comment "This is a pod"}]
                           [{:kind "Container"
                             :foo true} {:comment "This is a container"}]])
       $ {:walkers [(fn [node rule ctx]
                      (if (contains? (:spec node) :containers)
                        (update-in node [:spec :containers]
                                   #(map (fn [c] (rule c (merge ctx
                                                                {:kind "Container"}))) %))
                        ;; else
                        node))
                    (fn [node rule ctx]
                      (if (= (:kind node) "Deployment")
                        (update-in node [:spec :template] rule (merge ctx
                                                                      {:kind "Pod"
                                                                       :foo true}))
                         ;; else
                        node))]}
       depl (-> (lk/pod :foo {})
                (lk/add-container :bar "bar-image")
                (lk/add-container :baz "baz-image")
                (lk/deployment 3))]
   (lk/apply-aug-rule $ rule depl)
   => (-> (lk/pod :foo {})
          (assoc :comment "This is a pod")
          (lk/add-container :bar "bar-image" {:comment "This is a container"})
          (lk/add-container :baz "baz-image" {:comment "This is a container"})
          (lk/deployment 3)
          (assoc :comment "This is a deployment"))))

;; The `standard-descs` function provides walkers that extract
;; templates from deployments, and containers from pods.

;; For anything with a template (e.g., deployment, job, stateful-set),
;; a walker extracts its template, marking its `:kind` as `:Pod` and
;; pushing its metadata to the context.
(fact
 (let [rule (lk/aug-rules [[{:kind :Pod
                             :metadata {:name :foo
                                        :labels {:label :bar}
                                        :annotations {:some :annotation}}}
                            {:comment "This is a match!"}]])
       $ (-> (lk/injector)
             (lk/standard-descs))
       depl (-> (lk/pod :foo {:label :bar})
                (lk/stateful-set 3)
                (lk/add-annotation :some :annotation))]
   (lk/apply-aug-rule $ rule depl)
   => (-> (lk/pod :foo {:label :bar})
          (assoc :comment "This is a match!")
          (lk/stateful-set 3)
          (lk/add-annotation :some :annotation))))

;; Standard walkers enter each container in a pod, providing it the
;; context of its parent pod, marking its `:kind` as `:Container`.
(fact
 (let [rule (lk/aug-rules [[{:kind :Container
                             :metadata {:name :foo
                                        :labels {:label :bar}}}
                            {:comment "This is a match!"}]])
       $ (-> (lk/injector)
             (lk/standard-descs))
       depl (-> (lk/pod :foo {:label :bar})
                (lk/add-container :bar "some-image")
                (lk/job :Never))]
   (lk/apply-aug-rule $ rule depl)
   => (-> (lk/pod :foo {:label :bar})
          (lk/add-container :bar "some-image" {:comment "This is a match!"})
          (lk/job :Never))))

;; Init-containers are extracted as well, with `:kind` as
;; `:InitContainer`.
(fact
 (let [rule (lk/aug-rules [[{:kind :InitContainer
                             :metadata {:name :foo
                                        :labels {:label :bar}}}
                            {:comment "This is a match!"}]])
       $ (-> (lk/injector)
             (lk/standard-descs))
       depl (-> (lk/pod :foo {:label :bar})
                (lk/add-init-container :bar "some-image")
                (lk/job :Never))]
   (lk/apply-aug-rule $ rule depl)
   => (-> (lk/pod :foo {:label :bar})
          (lk/add-init-container :bar "some-image" {:comment "This is a match!"})
          (lk/job :Never))))
