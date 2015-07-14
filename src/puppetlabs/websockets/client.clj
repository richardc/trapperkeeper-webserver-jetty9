(ns puppetlabs.websockets.client)

(defprotocol WebSocketProtocol
  (send! [this msg])
  (close! [this])
  (remote-addr [this])
  (idle-timeout! [this ms]))