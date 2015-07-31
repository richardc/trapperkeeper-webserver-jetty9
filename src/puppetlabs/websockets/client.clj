(ns puppetlabs.websockets.client)

(defprotocol WebSocketProtocol
  (send! [this msg])
  (close! [this])
  (remote-addr [this])
  (ssl? [this])
  (peer-cn [this])
  (idle-timeout! [this ms])
  (connected? [this]))
