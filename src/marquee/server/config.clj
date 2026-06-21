(ns marquee.server.config
  (:require [clojure.string]))

(defn- env
  ([k]         (System/getenv k))
  ([k default] (or (System/getenv k) default)))

(defn strip-trailing-slashes
  "Removes any trailing slash(es) from a URL so consumers can safely append a
  leading-slash path without producing a double slash. Returns nil/blank
  values unchanged."
  [url]
  (if (clojure.string/blank? url)
    url
    (clojure.string/replace url #"/+$" "")))

(defn- url-env
  "Reads a URL env var and strips any trailing slashes."
  [k]
  (strip-trailing-slashes (env k)))

(def services
  {:pseudovision {:url       (url-env "PSEUDOVISION_URL")
                  :token     (env "PSEUDOVISION_TOKEN")
                  :spec-path (env "PSEUDOVISION_SPEC_PATH" "/openapi.json")}
   :tunarr-scheduler {:url       (url-env "TUNARR_SCHEDULER_URL")
                      :token     (env "TUNARR_SCHEDULER_TOKEN")
                      :spec-path (env "TUNARR_SCHEDULER_SPEC_PATH" "/openapi.json")}
   :tunabrain {:url       (url-env "TUNABRAIN_URL")
               :token     (env "TUNABRAIN_TOKEN")
               :spec-path (env "TUNABRAIN_SPEC_PATH" "/openapi.json")}})

;; Jellyfin is optional and not an OpenAPI-managed service.
(def jellyfin
  {:url   (url-env "JELLYFIN_URL")
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
