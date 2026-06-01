(ns marquee.server.dev)

;; Used by shadow-cljs :proxy-predicate to route only /api/* to the BFF.
(defn api-request? [req _config]
  (clojure.string/starts-with? (:uri req) "/api"))
