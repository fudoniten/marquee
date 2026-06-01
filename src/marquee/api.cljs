(ns marquee.api
  (:require [clojure.string :as str]
            [martian.re-frame :as martian])
  (:import [java.net URI]))

(defn append-url-path
  [url path]
  (let [u (js/URL. url)
        base-path (.-pathname u)
        extra-path (str path)
        joined-path (str
                     (str/replace base-path #"/+$" "")
                     "/"
                     (str/replace extra-path #"^/+" ""))]
    (set! (.-pathname u) joined-path)
    (.toString u)))

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

(defn bootstrap! [{:keys [::config/pseudovision ::config/tunarr-scheduler ::config/tunabrain]}]
  (let [{url :url} pseudovision]
    (martian/init (append-url-path url "/openapi.json")
                  {::martian/instance-id :pseudovision}))
  (let [{url :url} tunarr-scheduler]
    (martian/init (append-url-path url "/openapi.json")
                  {::martian/instance-id :tunarr-scheduler}))
  (let [{url :url} tunabrain]
    (martian/init (append-url-path url "/openapi.json")
                  {::martian/instance-id :tunabrain})))
