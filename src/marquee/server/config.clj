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
               :spec-path (env "TUNABRAIN_SPEC_PATH" "/openapi.json")}
   :grout {:url       (url-env "GROUT_URL")
           :token     (env "GROUT_TOKEN")
           :spec-path (env "GROUT_SPEC_PATH" "/openapi.json")}})

;; Jellyfin is optional and not an OpenAPI-managed service.
(def jellyfin
  {:url   (url-env "JELLYFIN_URL")
   :token (env "JELLYFIN_TOKEN")})

(defn- url-env-var [service-id]
  (str (-> service-id name (.replace "-" "_") .toUpperCase) "_URL"))

(defn configured?
  "True when service `id` has a non-blank URL and can be proxied to."
  [id]
  (not (clojure.string/blank? (get-in services [id :url]))))

(defn configured-services
  "The subset of `services` that have a URL set, as a map."
  []
  (into {} (filter (fn [[id _]] (configured? id)) services)))

(defn missing-service-urls
  "The `*_URL` env-var names for services that have no URL configured. Soft
  requirement: a missing URL disables that service rather than failing startup,
  so Marquee always boots (and a service can be deployed later without a
  Marquee restart — specs load lazily on first use)."
  []
  (vec (for [[id _] services
             :when (not (configured? id))]
         (url-env-var id))))
