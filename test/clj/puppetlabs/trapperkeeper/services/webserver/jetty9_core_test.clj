(ns puppetlabs.trapperkeeper.services.webserver.jetty9-core-test
  (:import
    (org.eclipse.jetty.server.handler ContextHandlerCollection)
    (java.security KeyStore)
    (java.net SocketTimeoutException Socket)
    (java.io InputStreamReader BufferedReader PrintWriter)
    (org.eclipse.jetty.server Server ServerConnector))
  (:require [clojure.test :refer :all]
            [clojure.java.jmx :as jmx]
            [ring.util.response :as rr]
            [puppetlabs.http.client.sync :as http-sync]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.trapperkeeper.services.webserver.jetty9-core :as jetty]
            [puppetlabs.trapperkeeper.testutils.webserver
             :refer [with-test-webserver with-test-webserver-and-config]]
            [puppetlabs.trapperkeeper.app :as tk-app]
            [puppetlabs.trapperkeeper.services.webserver.jetty9-default-config-test
             :refer [get-server-thread-pool-queue]]
            [puppetlabs.trapperkeeper.services.webserver.jetty9-service
             :refer [jetty9-service add-ring-handler]]
            [puppetlabs.trapperkeeper.testutils.logging :refer [with-test-logging]]
            [puppetlabs.trapperkeeper.testutils.bootstrap :refer [with-app-with-config]]
            [schema.test :as schema-test]))

(use-fixtures :once schema-test/validate-schemas)

(deftest handlers
  (testing "create-handlers should allow for handlers to be added"
    (let [webserver-context (jetty/initialize-context)
          handlers          (:handlers webserver-context)]
      (jetty/add-ring-handler webserver-context
                              (fn [req] {:status 200
                                         :body "I am a handler"})
                              "/"
                              true)
      (is (= (count (.getHandlers handlers)) 1)))))

(defn validate-gzip-encoding-when-gzip-requested
  [body port]
  ;; The client/get function asks for compression by default
  (let [resp (http-sync/get (format "http://localhost:%d/" port))]
    (is (= (slurp (resp :body)) body))
    (is (= (get-in resp [:orig-content-encoding]) "gzip")
        (format "Expected gzipped response, got this response: %s"
                resp))))

(defn validate-no-gzip-encoding-when-gzip-not-requested
  [body port]
  ;; The client/get function asks for compression by default
  (let [resp (http-sync/get (format "http://localhost:%d/" port)
                              {:decompress-body false})]
    (is (= (slurp (resp :body)) body))
    ;; We should not receive a content-encoding header in the
    ;; uncompressed case
    (is (nil? (get-in resp [:headers "content-encoding"]))
        (format "Expected uncompressed response, got this response: %s"
                resp))))

(defn validate-no-gzip-encoding-even-though-gzip-requested
  [body port]
  ;; The client/get function asks for compression by default
  (let [resp (http-sync/get (format "http://localhost:%d/" port))]
    (is (= (slurp (resp :body)) body))
    ;; We should not receive a content-encoding header in the
    ;; uncompressed case
    (is (nil? (get-in resp [:headers "content-encoding"]))
        (format "Expected uncompressed response, got this response: %s"
                resp))))

(deftest compression
  (testing "should return"
    ;; Jetty may not Gzip encode a response body if the size of the response
    ;; is less than 256 bytes, so returning a larger body to ensure that Gzip
    ;; encoding is used where desired for these tests
    (let [body (apply str (repeat 1000 "f"))
          app  (fn [req]
                 (-> body
                     (rr/response)
                     (rr/status 200)
                     (rr/content-type "text/plain")
                     (rr/charset "UTF-8")))]
      (with-test-webserver app port
        (testing "a gzipped response when request wants a compressed one and
                  server not configured with a default for gzip-enable"
          (validate-gzip-encoding-when-gzip-requested body port))

        (testing "an uncompressed response when request doesn't ask for a
                  compressed one and server not configured with a default for
                  gzip-enable"
          (validate-no-gzip-encoding-when-gzip-not-requested body port)))

      (with-test-webserver-and-config app port {:gzip-enable true}
         (testing "a gzipped response when request wants a compressed one and
                   server configured with a true value for gzip-enable"
           (validate-gzip-encoding-when-gzip-requested body port))

         (testing "an uncompressed response when request doesn't ask for a
                   compressed one and server configured with a true value for
                   gzip-enable"
           (validate-no-gzip-encoding-when-gzip-not-requested body port)))

      (with-test-webserver-and-config app port {:gzip-enable false}
         (testing "an uncompressed response when request wants a compressed one
                   but server configured with a false value for gzip-enable"
           (validate-no-gzip-encoding-even-though-gzip-requested body port))

         (testing "an uncompressed response when request doesn't ask for a
                   compressed one and server configured with a false value for
                   gzip-enable"
           (validate-no-gzip-encoding-when-gzip-not-requested body port))))))

(deftest jmx
  (testing "by default Jetty JMX support is enabled"
    (with-test-webserver #() _
      (testing "and should return a valid Jetty MBeans object"
        (let [mbeans (jmx/mbean-names "org.eclipse.jetty.jmx:*")]
          (is (not (empty? mbeans)))))

      (testing "and should not return data when we query for something unexpected"
        (let [mbeans (jmx/mbean-names "foobarbaz:*")]
          (is (empty? mbeans)))))))

(deftest override-webserver-settings!-tests
  (letfn [(webserver-context [state]
                             {:handlers (ContextHandlerCollection.)
                              :server   nil
                              :state    (atom state)})]
    (testing "able to associate overrides when overrides not already set"
      (let [context (webserver-context
                      {:some-other-state "some-other-value"})]
        (is (= {:host     "override-value-1"
                :ssl-host "override-value-2"}
               (jetty/override-webserver-settings!
                 context
                 {:host     "override-value-1"
                  :ssl-host "override-value-2"}))
            "Unexpected overrides returned from override-webserver-settings!")
        (is (= @(:state context)
               {:some-other-state "some-other-value"
                :overrides        {:host     "override-value-1"
                                   :ssl-host "override-value-2"}})
            "Unexpected config set for override-webserver-settings!")))
    (testing "unable to associate overrides when overrides already processed by
            webserver but overrides were not present"
      (let [context (webserver-context
                      {:some-other-config-setting   "some-other-value"
                       :overrides-read-by-webserver true})]
        (is (thrown-with-msg? java.lang.IllegalStateException
                              #"overrides cannot be set because webserver has already processed the config"
                              (jetty/override-webserver-settings!
                                context
                                {:host     "override-value-1"
                                 :ssl-host "override-value-2"}))
            "Call to override-webserver-settings! did not fail as expected.")
        (is (= {:some-other-config-setting   "some-other-value"
                :overrides-read-by-webserver true}
               @(:state context))
            "Config unexpectedly changed for override-webserver-settings!")))
    (testing "unable to associate override when overrides already processed by
            webserver and overrides were previously set"
      (let [context (webserver-context
                      {:some-other-config-setting   "some-other-value"
                       :overrides                   {:myoverride "my-override-value"}
                       :overrides-read-by-webserver true})]
        (is (thrown-with-msg? java.lang.IllegalStateException
                              #"overrides cannot be set because they have already been set and webserver has already processed the config"
                              (jetty/override-webserver-settings!
                                context
                                {:host     "override-value-1"
                                 :ssl-host "override-value-2"}))
            "Call to override-webserver-settings! did not fail as expected.")
        (is (= {:some-other-config-setting   "some-other-value"
                :overrides                   {:myoverride "my-override-value"}
                :overrides-read-by-webserver true}
               @(:state context))
            "Config unexpectedly changed for override-webserver-settings!")))
    (testing "unable to associate override when overrides were previously set"
      (let [context (webserver-context
                      {:some-other-config-setting "some-other-value"
                       :overrides                 {:myoverride "my-override-value"}})]
        (is (thrown-with-msg? java.lang.IllegalStateException
                              #"overrides cannot be set because they have already been set"
                              (jetty/override-webserver-settings!
                                context
                                {:host "override-value-1"
                                 :ssl-host "override-value-2"}))
            "Call to override-webserver-settings! did not fail as expected.")
        (is (= {:some-other-config-setting "some-other-value"
                :overrides                 {:myoverride "my-override-value"}}
               @(:state context))
            "config unexpectedly changed for override-webserver-settings!")))))

(defn get-webserver-context-for-server
  []
  {:state    (atom nil)
   :handlers (ContextHandlerCollection.)
   :server   nil})

(defn munge-common-connector-config
  [config connector-keyword]
  (-> config
      (update-in [connector-keyword :port] (fnil identity 0))
      (update-in [connector-keyword :host] (fnil identity "localhost"))
      (update-in [connector-keyword :request-header-max-size] identity)
      (update-in [connector-keyword :acceptor-threads] identity)
      (update-in [connector-keyword :selector-threads] identity)
      (update-in [connector-keyword :so-linger-milliseconds] identity)
      (update-in [connector-keyword :idle-timeout-milliseconds] identity)))

(defn munge-http-connector-config
  [config]
  (-> config
      (update-in [:max-threads] identity)
      (update-in [:queue-max-size] identity)
      (update-in [:jmx-enable] ks/parse-bool)
      (munge-common-connector-config :http)))

(defn munge-http-and-https-connector-config
  [config]
  (-> config
      (munge-http-connector-config)
      (munge-common-connector-config :https)
      (update-in [:https :protocols] identity)
      (update-in [:https :cipher-suites] identity)
      (update-in [:https :client-auth] (fnil identity :none))
      (update-in [:https :keystore-config]
                 (fnil identity
                       {:truststore (-> (KeyStore/getDefaultType)
                                        (KeyStore/getInstance))
                        :key-password "hello"
                        :keystore (-> (KeyStore/getDefaultType)
                                      (KeyStore/getInstance))}))))

(defn create-server-with-config
  [config]
  (jetty/create-server (get-webserver-context-for-server) config))

(defn create-server-with-partial-http-config
  [config]
  (create-server-with-config (munge-http-connector-config config)))

(defn create-server-with-partial-http-and-https-config
  [config]
  (create-server-with-config (munge-http-and-https-connector-config config)))

(defn get-thread-pool-for-partial-http-config
  [config]
  (.getThreadPool (create-server-with-partial-http-config config)))

(def get-thread-pool-for-default-server (.getThreadPool (Server.)))

(def default-server-max-threads (.getMaxThreads
                                  get-thread-pool-for-default-server))

(defn get-max-threads-for-partial-http-config
  [config]
  (.getMaxThreads (get-thread-pool-for-partial-http-config config)))

(deftest create-server-max-threads-test
  (testing "default max threads passed through to thread pool"
    (is (= default-server-max-threads
           (get-max-threads-for-partial-http-config {:max-threads nil}))))
  (testing "custom max threads passed through to thread pool"
    (is (= 9042
           (get-max-threads-for-partial-http-config
             {:max-threads 9042, :queue-max-size nil})))))

(deftest create-server-queue-max-size-test
  (let [get-queue-for-partial-http-config (fn [config]
                                            (get-server-thread-pool-queue
                                              (create-server-with-config
                                                (munge-http-connector-config
                                                  config))))
        default-server-min-threads        (.getMinThreads
                                            get-thread-pool-for-default-server)]
    (testing "default queue max size passed through to thread pool queue"
      (is (= (.getMaxCapacity (get-server-thread-pool-queue (Server.)))
             (.getMaxCapacity (get-queue-for-partial-http-config
                                {:queue-max-size nil})))))
    (testing "custom default queue max size passed through to thread pool queue"
      (is (= 393
             (.getMaxCapacity (get-queue-for-partial-http-config
                                {:queue-max-size 393})))))
    (testing (str "default max threads passed through to thread pool when "
                  "queue-max-size set")
      (is (= default-server-max-threads
             (get-max-threads-for-partial-http-config
               {:max-threads nil, :queue-max-size 1}))))
    (testing "min threads passed through to thread pool when queue-max-size set"
      (is (= default-server-min-threads
             (.getMinThreads (get-thread-pool-for-partial-http-config
                               {:queue-max-size 1})))))
    (testing "idle timeout passed through to thread pool when queue-max-size set"
      (is (= (.getIdleTimeout get-thread-pool-for-default-server)
             (.getIdleTimeout (get-thread-pool-for-partial-http-config
                                {:queue-max-size 1})))))
    (testing (str "queue min size set on thread pool queue equal to min threads "
                  "when queue max size greater than min threads")
      (is (= default-server-min-threads
             (.getCapacity (get-queue-for-partial-http-config
                             {:queue-max-size
                              (inc default-server-min-threads)})))))
    (testing (str "queue min size set on thread pool queue equal to queue max "
                  "size when queue max size less than min threads")
      (let [queue-max-size (dec default-server-min-threads)]
        (is (= queue-max-size
               (.getCapacity (get-queue-for-partial-http-config
                               {:queue-max-size queue-max-size}))))))))

(deftest create-server-so-linger-test
  (testing "so-linger-time configured properly for http connector"
    (let [server     (create-server-with-partial-http-config
                       {:http {:so-linger-milliseconds 500}})
          connectors (.getConnectors server)]
      (is (= 1 (count connectors))
          "Unexpected number of connectors for server")
      (is (= 500 (.getSoLingerTime (first connectors)))
          "Unexpected so linger time for connector")))
  (testing "so-linger-time configured properly for multiple connectors"
    (let [server (create-server-with-partial-http-and-https-config
                   {:http  {:port 25
                            :so-linger-milliseconds 41}
                    :https {:port 92
                            :so-linger-milliseconds 42}})
          connectors (.getConnectors server)]
      (is (= 2 (count connectors))
          "Unexpected number of connectors for server")
      (is (= 25 (.getPort (first connectors)))
          "Unexpected port for first connector")
      (is (= 41 (.getSoLingerTime (first connectors)))
          "Unexpected so linger time for first connector")
      (is (= 92 (.getPort (second connectors)))
          "Unexpected port for second connector")
      (is (= 42 (.getSoLingerTime (second connectors)))
          "Unexpected so linger time for second connector"))))

(deftest create-server-idle-timeout-test
  (testing "idle-timeout configured properly for http connector"
    (let [server (create-server-with-partial-http-config
                   {:http {:idle-timeout-milliseconds 3000}})
          connectors (.getConnectors server)]
      (is (= 1 (count connectors))
          "Unexpected number of connectors for server")
      (is (= 3000 (.getIdleTimeout (first connectors)))
          "Unexpected idle time for connector")))
  (testing "idle-timeout configured properly for multiple connectors"
    (let [server     (create-server-with-partial-http-and-https-config
                       {:http  {:port 25
                                :idle-timeout-milliseconds 9001}
                        :https {:port 92
                                :idle-timeout-milliseconds 9002}})
          connectors (.getConnectors server)]
      (is (= 2 (count connectors))
          "Unexpected number of connectors for server")
      (is (= 25 (.getPort (first connectors)))
          "Unexpected port for first connector")
      (is (= 9001 (.getIdleTimeout (first connectors)))
          "Unexpected idle timeout for first connector")
      (is (= 92 (.getPort (second connectors)))
          "Unexpected port for second connector")
      (is (= 9002 (.getIdleTimeout (second connectors)))
          "Unexpected idle time for second connector"))))

(deftest create-server-acceptor-threads-test
  (testing "nil acceptors configured properly for http connector"
    (let [server     (create-server-with-partial-http-config
                       {:http {:acceptor-threads nil}})
          connectors (.getConnectors server)]
      (is (= 1 (count connectors))
          "Unexpected number of connectors for server")
      (is (= (.getAcceptors (ServerConnector. (Server.)))
             (.getAcceptors (first connectors)))
          "Unexpected number of acceptor threads for connector")))
  (testing "non-nil acceptors configured properly for http connector"
    (let [server     (create-server-with-partial-http-config
                       {:http {:acceptor-threads 42}})
          connectors (.getConnectors server)]
      (is (= 1 (count connectors))
          "Unexpected number of connectors for server")
      (is (= 42 (.getAcceptors (first connectors)))
          "Unexpected number of acceptor threads for connector")))
  (testing "non-nil acceptors configured properly for multiple connectors"
    (let [server (create-server-with-partial-http-and-https-config
                   {:http  {:port 25
                            :acceptor-threads 91}
                    :https {:port 92
                            :acceptor-threads 63}})
          connectors (.getConnectors server)]
      (is (= 2 (count connectors))
          "Unexpected number of connectors for server")
      (is (= 25 (.getPort (first connectors)))
          "Unexpected port for first connector")
      (is (= 91 (.getAcceptors (first connectors)))
          "Unexpected number of acceptor threads for first connector")
      (is (= 92 (.getPort (second connectors)))
          "Unexpected port for second connector")
      (is (= 63 (.getAcceptors (second connectors)))
          "Unexpected number of acceptor threads for second connector"))))

(deftest create-server-selector-threads-test
  (letfn [(selector-threads [connector]
                              (-> connector
                                  (.getSelectorManager)
                                  (.getSelectorCount)))]
    (testing "nil selectors configured properly for http connector"
      (let [server (create-server-with-partial-http-config
                     {:http {:selector-threads nil}})
            connectors (.getConnectors server)]
        (is (= 1 (count connectors))
            "Unexpected number of connectors for server")
        (is (= (selector-threads (ServerConnector. (Server.)))
               (selector-threads (first connectors)))
            "Unexpected number of selectors for connector")))
    (testing "non-nil selectors configured properly for http connector"
      (let [server     (create-server-with-partial-http-config
                         {:http {:selector-threads 42}})
            connectors (.getConnectors server)]
        (is (= 1 (count connectors))
            "Unexpected number of connectors for server")
        (is (= 42 (selector-threads (first connectors)))
            "Unexpected number of selector threads for connector")))
    (testing "non-nil selectors configured properly for multiple connectors"
      (let [server (create-server-with-partial-http-and-https-config
                     {:http  {:port 25
                              :selector-threads 91}
                      :https {:port 92
                              :selector-threads 63}})
            connectors (.getConnectors server)]
        (is (= 2 (count connectors))
            "Unexpected number of connectors for server")
        (is (= 25 (.getPort (first connectors)))
            "Unexpected port for first connector")
        (is (= 91 (selector-threads (first connectors)))
            "Unexpected number of selector threads for first connector")
        (is (= 92 (.getPort (second connectors)))
            "Unexpected port for second connector")
        (is (= 63 (selector-threads (second connectors)))
            "Unexpected number of selector threads for second connector")))))

(deftest test-idle-timeout
  (let [read-lines (fn [r]
                     (let [sb (StringBuilder.)]
                       (loop [l (.readLine r)]
                         (when l
                           (.append sb l)
                           (.append sb "\n")
                           ;; readLine will block until the socket is closed,
                           ;; or will throw a SocketTimeoutException if there
                           ;; is no data available within the SoTimeout value.
                           (recur (.readLine r))))
                       (.toString sb)))
        body "Hi World\n"
        path "/hi_world"
        ring-handler (fn [req] {:status 200 :body body})
        read-response (fn [client-so-timeout]
                        (let [s (Socket. "localhost" 9000)
                              out (PrintWriter. (.getOutputStream s) true)]
                          (.setSoTimeout s client-so-timeout)
                          (.println out (str "GET " path " HTTP/1.1\n"
                                             "Host: localhost\n"
                                             "\n"))
                          (let [in (BufferedReader. (InputStreamReader. (.getInputStream s)))]
                            (read-lines in))))]
    (let [config {:webserver {:port 9000
                              :host "localhost"
                              :idle-timeout-milliseconds 500}}]
      (with-test-logging
        (with-app-with-config app
          [jetty9-service]
          config
          (let [s (tk-app/get-service app :WebserverService)
                add-ring-handler (partial add-ring-handler s)]
            (add-ring-handler ring-handler path)

            (testing "Verify that server doesn't close socket before idle timeout"
              ;; if we set the client socket timeout lower than the server
              ;; socket timeout, we should get a timeout exception from the
              ;; client side while attempting to read from the socket.
              (is (thrown-with-msg? SocketTimeoutException #"Read timed out"
                                    (read-response 250))))
            (testing "Verify that server closes the socket after idle timeout"
              ;; if we set the client socket timeout higher than the server,
              ;; then the server should close the socket after its timeout,
              ;; which will cause our read to stop blocking and allow us to
              ;; validate the contents of the data we read from the socket.
              (let [resp (read-response 750)]
                (is (re-find #"(?is)HTTP.*200 OK.*Hi World"
                             resp))))))))))
