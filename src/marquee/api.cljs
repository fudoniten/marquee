(ns marquee.api
  (:require [martian.re-frame :as martian]))

;; Initializes one martian instance per backend service, routed through the BFF.
;; The frontend only knows service IDs — the BFF holds the real URLs and tokens.
;;
;; This list should mirror the keys in server/config.clj. No URLs or tokens here.
;;
;; Dispatching a request:
;;   (rf/dispatch [::martian/request
;;                 :some-operation                            ;; operationId from the OpenAPI spec
;;                 {::martian/instance-id :pseudovision       ;; service key
;;                  :param "value"}                           ;; martian handles path/query/body
;;                 [::on-success]
;;                 [::on-failure]])

(def ^:private services
  [:pseudovision
   :tunarr-scheduler
   :tunabrain])

(defn bootstrap! []
  (doseq [id services]
    (martian/init (str "/api/" (name id) "/openapi.json")
                  {::martian/instance-id id})))
