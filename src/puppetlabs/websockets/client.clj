(ns puppetlabs.websockets.client)

(defprotocol WebSocketProtocol
  (send! [this msg])
  (close! [this])
  (remote-addr [this])
  (ssl? [this])
  (peer-certs [this])
  (idle-timeout! [this ms])
  (connected? [this]))
