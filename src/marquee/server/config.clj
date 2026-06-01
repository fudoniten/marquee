(ns marquee.server.config)

(defn- env
  ([k]         (System/getenv k))
  ([k default] (or (System/getenv k) default)))

;; Add one entry per OpenAPI-enabled backend service.
;;
;; Keys:
;;   :url       – base URL of the service (no trailing slash)
;;   :token     – Bearer token for Authorization header (nil = no auth)
;;   :spec-path – path to the OpenAPI spec (default "/openapi.json")
;;
;; Example:
;;   {:payments {:url       (env "PAYMENTS_URL" "http://localhost:9001")
;;               :token     (env "PAYMENTS_TOKEN")
;;               :spec-path "/openapi.json"}
;;    :inventory {:url      (env "INVENTORY_URL" "http://localhost:9002")
;;                :token    (env "INVENTORY_TOKEN")}}

(def services {})
