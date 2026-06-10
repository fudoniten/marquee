(ns marquee.server.config
  (:require [clojure.string]))

(defn- env
  ([k]         (System/getenv k))
  ([k default] (or (System/getenv k) default)))

(def services
  {:pseudovision {:url       (env "PSEUDOVISION_URL")
                  :token     (env "PSEUDOVISION_TOKEN")
                  :spec-path (env "PSEUDOVISION_SPEC_PATH" "/openapi.json")}
   :tunarr-scheduler {:url       (env "TUNARR_SCHEDULER_URL")
                      :token     (env "TUNARR_SCHEDULER_TOKEN")
                      :spec-path (env "TUNARR_SCHEDULER_SPEC_PATH" "/openapi.json")}
   :tunabrain {:url       (env "TUNABRAIN_URL")
               :token     (env "TUNABRAIN_TOKEN")
               :spec-path (env "TUNABRAIN_SPEC_PATH" "/openapi.json")}})

;; Jellyfin is optional and not an OpenAPI-managed service.
(def jellyfin
  {:url   (env "JELLYFIN_URL")
   :token (env "JELLYFIN_TOKEN")})

(defn- url-env-var [service-id]
  (str (-> service-id name (.replace "-" "_") .toUpperCase) "_URL"))

(defn validate!
  "Throws if any service is missing its required *_URL env var."
  []
  (let [missing (for [[id {:keys [url]}] services
                      :when (clojure.string/blank? url)]
                  (url-env-var id))]
    (when (seq missing)
      (throw (ex-info (str "Missing required environment variables: "
                           (clojure.string/join ", " missing))
                      {:missing (vec missing)})))))
