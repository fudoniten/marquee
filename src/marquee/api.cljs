(ns marquee.api
  (:require [martian.re-frame :as martian]))

;; Call bootstrap! once at app startup. It fetches each service's OpenAPI spec
;; and registers martian's re-frame handlers for that service.
;;
;; Dispatching a request (single/default service):
;;   (rf/dispatch [::martian/request
;;                 :operation-id          ;; matches operationId in the OpenAPI spec
;;                 {:param "value"}       ;; path/query/body params as a flat map
;;                 [::on-success]
;;                 [::on-failure]])
;;
;; Dispatching a request (named service instance):
;;   (rf/dispatch [::martian/request
;;                 :service-name          ;; instance-id used in bootstrap!
;;                 :operation-id
;;                 {:param "value"}
;;                 [::on-success]
;;                 [::on-failure]])

(defn bootstrap! []
  ;; Add one martian/init call per OpenAPI-enabled backend service.
  ;;
  ;; Single service:
  ;;   (martian/init "/openapi.json")
  ;;
  ;; Multiple services (use ::martian/instance-id to distinguish them):
  ;;   (martian/init "https://service-a.example.com/openapi.json"
  ;;                 {::martian/instance-id :service-a})
  ;;   (martian/init "https://service-b.example.com/openapi.json"
  ;;                 {::martian/instance-id :service-b})
  )
