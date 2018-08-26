(ns lambdakube.core-test
  (:require [midje.sweet :refer :all]
            [lambdakube.core :as lk]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]))

;; # Basic API Object Functions

;; The following functions create basic API objects.

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
            :serviceName :foo
            :template
            {:metadata
             {:labels {:bar :baz}}
             :spec
             {:containers
              [{:name :bar
                :image "some-image"}]}}
            :volumeClaimTemplates []}})

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
            :serviceName :foo
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
            :serviceName :foo
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
;; function. This function takes a name of a container, a port number
;; on that container and (optionally) a port number to be exported,
;; and returns a function that transforms both a pod and a service.
(fact
 (let [p (lk/port :my-cont 80 8080)
       ;; Based on the kind of service, we provide a function that
       ;; updates the service with the new ports.
       edit-svc (fn [svc src tgt]
                  (update svc :spec lk/field-conj :ports
                          {:port src :targetPort tgt}))
       pod (-> (lk/pod :my-pod {})
               (lk/add-container :my-cont "some-image"))
       svc {:metadata {:name :foo}
            :spec {}}
       [pod svc] (p [pod svc edit-svc])]
   pod => (-> (lk/pod :my-pod {})
              (lk/add-container :my-cont "some-image" {:ports [{:containerPort 80}]}))
   svc => {:metadata {:name :foo}
           :spec {:ports [{:port 80
                           :targetPort 8080}]}}))

;; `port` is composable through composition (`comp`).
(let [p (comp (lk/port :my-cont 80 8080)
              (lk/port :my-cont 443 443))
       edit-svc (fn [svc src tgt]
                  (update svc :spec lk/field-conj :ports
                          {:port src :targetPort tgt}))
       pod (-> (lk/pod :my-pod {})
               (lk/add-container :my-cont "some-image"))
       svc {:metadata {:name :foo}
            :spec {}}
       [pod svc] (p [pod svc edit-svc])]
   pod => (-> (lk/pod :my-pod {})
              (lk/add-container :my-cont "some-image" {:ports [{:containerPort 80}
                                                               {:containerPort 443}]}))
   svc => {:metadata {:name :foo}
           :spec {:ports [{:port 80
                           :targetPort 8080}
                          {:port 443
                           :targetPort 443}]}})

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
                (lk/port :quux 80 30080)
                {:type :NodePort}
                (fn [svc src tgt]
                  (update svc :spec lk/field-conj :ports
                          {:port src :nodePort tgt}))))
 => {:apiVersion "apps/v1"
     :kind "Deployment"
     :metadata {:name :foo
                :labels {:bar :baz}}
     :spec {:replicas 3
            :selector {:matchLabels {:bar :baz}}
            :template {:metadata {:labels {:bar :baz}}
                       :spec {:containers [{:name :quux
                                            :image "some-image"
                                            :ports [{:containerPort 80}]}]}}}
     :$additional {:apiVersion "v1"
                   :kind "Service"
                   :metadata {:name :foo-srv}
                   :spec {:type :NodePort
                          :selector {:bar :baz}
                          :ports [{:port 80
                                   :nodePort 30080}]}}})

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
                (lk/port :quux 80 8080)))
 => {:apiVersion "apps/v1"
     :kind "Deployment"
     :metadata {:name :foo
                :labels {:bar :baz}}
     :spec {:replicas 3
            :selector {:matchLabels {:bar :baz}}
            :template {:metadata {:labels {:bar :baz}}
                       :spec {:containers [{:name :quux
                                            :image "some-image"
                                            :ports [{:containerPort 80}]}]}}}
     :$additional {:apiVersion "v1"
                   :kind "Service"
                   :metadata {:name :foo-srv}
                   :spec {:type :ClusterIP
                          :selector {:bar :baz}
                          :ports [{:port 80
                                   :targetPort 8080}]}}})

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

;; Resources may depend on one another. The following module depends
;; on `:my-service`.
(defn module3 [$]
  (-> $
      (lk/rule :my-service [:my-deployment-num-replicas]
                (fn [num-replicas]
                  (-> (lk/pod :my-service {:app :my-app})
                      (lk/deployment num-replicas))))))

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
 => (concat [(-> (lk/pod :my-service {:app :my-app})
                 (lk/deployment 5))]
            [(lk/pod :my-pod {:app :my-app})]))

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
                  {:name (-> obj :metadata :name)}))
      (lk/desc (fn [obj]
                  (when (= (:kind "Service"))
                    {:port (-> obj :spec :ports first :port)})))
      (lk/desc (fn [obj]
                  {:labels (-> obj :metadata :labels)}))
      (lk/rule :first-pod []
                (fn []
                  (lk/pod :my-first-pod {})))
      (lk/rule :second-pod [:first-pod]
                (fn [first-pod]
                  (lk/pod :my-first-pod {:the-name (:name first-pod)
                                          :the-port (:port first-pod)
                                          :the-labels (:labels first-pod)})))))

;; The module also defines two rules for two pods. The second pod
;; depends on the first one, and populates its labels with information
;; about the first pod (not a real-life scenario). When we call
;; `get-deployable`, we will get both pods. The labels in the second
;; pod will be set so that the name will be there, but not the port.
(fact
 (-> (lk/injector)
     (module6)
     (lk/get-deployable {}))
 => [(lk/pod :my-first-pod {})
     (lk/pod :my-first-pod {:the-name :my-first-pod
                             :the-port nil
                             :the-labels {}})])


;; # Utility Functions

;; All the above functions are pure functions that help build
;; Kubernetes API objects for systems. The following functions help
;; translate these objects into a real update of the state of a
;; Kubernetes cluster.

;; `to-yaml` takes a vector of API objects and returns a YAML string
;; acceptable by Kubernetes.
(fact
 (-> (lk/pod :nginx-deployment {:app :nginx})
     (lk/add-container :nginx "nginx:1.7.9" {:ports [{:containerPort 80}]})
     (lk/deployment 3)
     (list)
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
      - ports:
        - containerPort: 80
        name: nginx
        image: nginx:1.7.9
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

;; # Turning this to Usable YAML Files

'(println (-> (lk/pod :nginx-deployment {:app :nginx})
             (lk/add-container :nginx "nginx:1.7.9" {:ports [{:containerPort 80}]})
             (lk/deployment 3)
             (lk/expose-headless)
             (lk/to-yaml)))

'(println (-> (lk/pod :nginx {:app :nginx} {:terminationGracePeriodSeconds 10})
             (lk/add-container :nginx "k8s.gcr.io/nginx-slim:0.8" {:ports [{:containerPort 80
                                                                             :name "web"}]})
             (lk/stateful-set 3)
             (lk/add-volume-claim-template :www
                                            {:accessModes ["ReadWriteOnce"]
                                             :resources {:requests {:storage "1Gi"}}}
                                            {:nginx "/usr/share/nginx/html"})
             (lk/expose-headless)
             (lk/to-yaml)))


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

;; `:$additional` fields can appear anywhere in the structure.
(fact
 (let [ext (lk/extract-additional {:foo :bar
                                   :baz {:x 1
                                         :$additional [{:z 3}]}
                                   :quux [{:p 1
                                           :$additional [{:y 2}]}]})]
   ext => {:foo :bar
           :baz {:x 1}
           :quux [{:p 1}]}
   (set (-> ext meta :additional)) => #{{:z 3}
                                        {:y 2}}))

