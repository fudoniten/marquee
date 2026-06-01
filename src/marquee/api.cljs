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
