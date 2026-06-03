(ns marquee.server.config)

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
