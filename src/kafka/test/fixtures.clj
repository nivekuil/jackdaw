(ns kafka.test.fixtures
  "Test fixtures for kafka based apps"
  (:require
   [clojure.test :as test]
   [clojure.tools.logging :as log]
   [kafka.test.config :as config]
   [kafka.test.kafka :as broker]
   [kafka.test.zk :as zk])
  (:import
   (io.confluent.kafka.schemaregistry.rest SchemaRegistryRestApplication SchemaRegistryConfig)
   (kafka.common TopicExistsException)
   (org.apache.kafka.clients.consumer KafkaConsumer ConsumerRecord)
   (org.apache.kafka.clients.producer KafkaProducer ProducerRecord Callback)))

;; services

(defn zookeeper
  "A zookeeper test fixture

   Start up a zookeeper broker with the supplied config before running
   the test `t`"
  [config]
  (fn [t]
    (let [zk (zk/start! {:config config})]
      (try
        (log/info "Started zookeeper fixture" zk)
        (t)
        (finally
          (zk/stop! zk)
          (log/info "Stopped zookeeper fixture" zk))))))

(defn broker
  "A kafka test fixture.

   Start up a kafka broker with the supplied config before running the
   test `t`"
  [config]
  (when-not (get config "log.dirs")
    (throw (ex-info "Invalid config: missing required field 'log.dirs'"
                    {:config config})))

  (fn [t]
    (let [log-dirs (get config "log.dirs")
          kafka (broker/start! {:config config})]
      (try
        (log/info "Started kafka fixture" kafka)
        (t)
        (finally
          (broker/stop! (merge kafka {:log-dirs log-dirs}))
          (log/info "Stopped kafka fixture" kafka))))))

(defn multi-broker
  "A multi-broker kafka fixture

   Starts up `n` kafka brokers by generating a vector of configs
   by invoking the `multi-config` function on each integer up to `n`."
  [config n]
  (fn [t]
    (let [multi-config (config/multi-config config)
          configs (map multi-config (range n))
          cluster (doall (map (fn [cfg]
                                (assoc (broker/start! {:config cfg})
                                       :log-dirs (get cfg "log.dirs")))
                              configs))]
      (try
        (log/info "Started multi-broker fixture" cluster)
        (t)
        (finally
          ;; This takes a surprisingly
          (doseq [node (reverse cluster)]
            (broker/stop! node))
          (log/info "Stopped multi-broker fixture" cluster))))))

(defn schema-registry
  [config]
  (fn [t]
    (let [app (SchemaRegistryRestApplication.
               (SchemaRegistryConfig. (config/properties config)))
          server (.createServer app)]
      (try
        (.start server)
        (log/info "Started schema registry fixture" server)
        (t)
        (finally
          (.stop server)
          (log/info "Stopped schema registry fixture" server))))))

;; fixture composition

(defn identity-fixture
  "They have this already in clojure.test but it is called
   `default-fixture` and it is private. Probably stu seirra's fault
   :troll:"
  [t]
  (t))

(defn kafka-platform
  "Combine the zookeeper, broker, and schema-registry fixtures into
   one glorius test helper"
  [zk-config broker-config embedded?]
  (if embedded?
    (test/compose-fixtures (zookeeper zk-config)
                           (broker broker-config))
    identity-fixture))
