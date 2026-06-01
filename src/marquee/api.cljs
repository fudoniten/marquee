(ns marquee.api
  (:require [martian.re-frame :as martian]))

;; Initializes martian for each backend service via the BFF.
;; The BFF fetches the real OpenAPI spec and rewrites its server URL to
;; point back through itself, so auth tokens never touch the browser.
;;
;; Add one martian/init call per service defined in server/config.clj.
;; The path matches /api/{service-id}/openapi.json on the BFF.
;;
;; Example (single service, default martian instance):
;;   (martian/init "/api/payments/openapi.json")
;;
;; Example (multiple services, named instances):
;;   (martian/init "/api/payments/openapi.json"  {::martian/instance-id :payments})
;;   (martian/init "/api/inventory/openapi.json" {::martian/instance-id :inventory})
;;
;; Dispatching a request (single/default instance):
;;   (rf/dispatch [::martian/request
;;                 :list-orders        ;; operationId from the spec
;;                 {:status "pending"} ;; params — martian handles path/query/body
;;                 [::on-success]
;;                 [::on-failure]])
;;
;; Dispatching with a named instance:
;;   (rf/dispatch [::martian/request
;;                 :payments           ;; instance-id
;;                 :create-payment
;;                 {:amount 100}
;;                 [::on-success]
;;                 [::on-failure]])

(defn bootstrap! []
  ;; TODO: add one martian/init call per entry in server/config.clj
  ;; (martian/init "/api/payments/openapi.json" {::martian/instance-id :payments})
  )
