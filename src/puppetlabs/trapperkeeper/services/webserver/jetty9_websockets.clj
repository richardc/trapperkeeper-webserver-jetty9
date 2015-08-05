(ns puppetlabs.trapperkeeper.services.webserver.jetty9-websockets
  (:import (org.eclipse.jetty.websocket.api WebSocketAdapter Session)
           (org.eclipse.jetty.websocket.server WebSocketHandler)
           (org.eclipse.jetty.websocket.servlet WebSocketServletFactory WebSocketCreator)
           (java.nio ByteBuffer))

  (:require [clojure.tools.logging :as log]
            [puppetlabs.websockets.client :refer [WebSocketProtocol]]
            [schema.core :as schema]))


(defprotocol WebSocketSend
  (-send! [x ws] "How to encode content sent to the WebSocket clients"))

(extend-protocol WebSocketSend
  (Class/forName "[B")
  (-send! [ba ws]
    (-send! (ByteBuffer/wrap ba) ws))

  ByteBuffer
  (-send! [bb ws]
    (-> ^WebSocketAdapter ws .getRemote (.sendBytes ^ByteBuffer bb)))

  String
  (-send! [s ws]
    (-> ^WebSocketAdapter ws .getRemote (.sendString ^String s))))

(extend-protocol WebSocketProtocol
  WebSocketAdapter
  (send! [this msg]
    (-send! msg this))
  (close! [this]
    (.. this (getSession) (close)))
  (remote-addr [this]
    (.. this (getSession) (getRemoteAddress)))
  (ssl? [this]
    (.. this (getSession) (getUpgradeRequest) (isSecure)))
  (peer-certs [this]
    (.. this (getCerts)))
  (idle-timeout! [this ms]
    (.. this (getSession) (setIdleTimeout ^long ms)))
  (connected? [this]
    (. this (isConnected))))

(defn- do-nothing [& args])

(definterface CertGetter
  (^Object getCerts []))

(defn proxy-ws-adapter
  [{:as handlers
    :keys [on-connect on-error on-text on-close on-bytes]
    :or {on-connect do-nothing
         on-error do-nothing
         on-text do-nothing
         on-close do-nothing
         on-bytes do-nothing}} x509certs]
  (proxy [WebSocketAdapter CertGetter] []
    (onWebSocketConnect [^Session session]
      (let [^WebSocketAdapter this this]
        (proxy-super onWebSocketConnect session))
      (on-connect this))
    (onWebSocketError [^Throwable e]
      (on-error this e))
    (onWebSocketText [^String message]
      (on-text this message))
    (onWebSocketClose [statusCode ^String reason]
      (let [^WebSocketAdapter this this]
        (proxy-super onWebSocketClose statusCode reason))
      (on-close this statusCode reason))
    (onWebSocketBinary [^bytes payload offset len]
      (on-bytes this payload offset len))
    (getCerts [] x509certs)))

(defn proxy-ws-creator
  [handlers]
  (reify WebSocketCreator
    (createWebSocket [this req _]
      (let [x509certs (.. req (getCertificates))]
        (proxy-ws-adapter handlers x509certs)))))
