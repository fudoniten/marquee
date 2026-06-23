(ns marquee.server.core
  (:require [org.httpkit.server :as http]
            [ring.middleware.json :refer [wrap-json-response]]
            [ring.util.response :as resp]
            [clj-http.client :as client]
            [marquee.server.config :as config]
            [clojure.string :as str]))

;; Holds fetched+rewritten specs keyed by service-id, populated at startup.
(defonce spec-cache (atom {}))

(defn- add-operation-ids
  "Add operationId to endpoints that are missing it, using the pattern:
   {method}-{path-with-dashes}, e.g. 'get-api-media-items-id'
   
   Handles path parameters correctly by removing braces and normalizing dashes,
   and uses lowercase method names to match martian's kebab-case keyword conversion."
  [paths]
  (reduce-kv
   (fn [acc path methods]
     (let [;; Convert path to a safe operation name part (remove leading slash, replace special chars)
           path-part (-> path
                        (str/replace #"^/" "")                    ;; Remove leading slash
                        (str/replace #"[{}]" "")                  ;; Remove braces (don't replace with dash)
                        (str/replace #"[/_]" "-")                 ;; Replace other special chars
                        (str/replace #"-+" "-")                   ;; Normalize consecutive dashes
                        (str/replace #"-+$" ""))                  ;; Remove trailing dashes
           ;; Add operationId to each method if missing
           updated-methods (reduce-kv
                           (fn [m-acc method details]
                             (assoc m-acc method
                                    (if (get details "operationId")
                                      details
                                      (assoc details "operationId"
                                             (str (-> method name .toLowerCase) "-" path-part)))))
                           {}
                           methods)]
       (assoc acc path updated-methods)))
   {}
   paths))

(defn- rewrite-spec [service-id spec]
  (let [base-path (str "/api/" (name service-id))]
    ;; Rewrite the server URL so the frontend's martian routes through the BFF.
    ;; Also ensure all endpoints have operationId for martian route generation.
    (cond-> spec
      (get spec "openapi")
      (assoc "servers" [{"url" base-path}])                           ; OpenAPI 3.x
      
      (not (get spec "openapi"))
      (assoc "basePath" base-path "host" "" "schemes" [])             ; Swagger 2.0
      
      (get spec "paths")
      (update "paths" add-operation-ids))))

(defn fetch-spec!
  "Fetches the upstream OpenAPI spec. Throws ex-info with a clear message on
  any failure (network error, non-200 status, non-JSON body)."
  [service-id {:keys [url spec-path] :or {spec-path "/openapi.json"}}]
  (let [target (str url spec-path)
        response (try
                   (client/get target {:as :json-string-keys :throw-exceptions false})
                   (catch Exception e
                     (throw (ex-info (str "Could not reach " target ": " (.getMessage e))
                                     {:service service-id :url target}
                                     e))))]
    (when-not (= 200 (:status response))
      (throw (ex-info (str "Upstream " target " returned status " (:status response))
                      {:service service-id :url target :status (:status response)})))
    (let [spec (:body response)]
      (when-not (map? spec)
        (throw (ex-info (str "Upstream " target " did not return a JSON object")
                        {:service service-id :url target})))
      (rewrite-spec service-id spec))))

(defn preload-specs!
  "Fetches every configured service's spec into the cache. Throws if any fail,
  with a combined message listing all problems."
  []
  (let [results (for [[id svc] config/services]
                  (try
                    [id (fetch-spec! id svc)]
                    (catch Exception e
                      [id e])))
        errors  (filter #(instance? Throwable (second %)) results)]
    (when (seq errors)
      (throw (ex-info (str "Failed to load upstream specs:\n  - "
                           (str/join "\n  - "
                                     (for [[id e] errors]
                                       (str (name id) ": " (.getMessage ^Throwable e)))))
                      {:errors (into {} (for [[id e] errors] [id (.getMessage ^Throwable e)]))})))
    (reset! spec-cache (into {} results))))

(defn- proxy-to-backend [service-id {:keys [url token]} req]
  (let [prefix   (str "/api/" (name service-id))
        path     (subs (:uri req) (count prefix))
        target   (str url (if (str/blank? path) "/" path))
        headers  (cond-> (dissoc (:headers req) "host" "content-length")
                   token (assoc "authorization" (str "Bearer " token)))
        response (client/request {:method          (:request-method req)
                                  :url             target
                                  :query-string    (:query-string req)
                                  :headers         headers
                                  :body            (:body req)
                                  :as              :stream
                                  :throw-exceptions false})]
    {:status  (:status response)
     :headers (dissoc (:headers response) "transfer-encoding" "content-length")
     :body    (:body response)}))

(defn- proxy-to-jellyfin [req]
  (let [{:keys [url token]} config/jellyfin
        path     (subs (:uri req) (count "/api/jellyfin"))
        target   (str url (if (str/blank? path) "/" path))
        headers  (cond-> (dissoc (:headers req) "host" "content-length")
                   token (assoc "x-emby-token" token))
        response (client/request {:method          (:request-method req)
                                  :url             target
                                  :query-string    (:query-string req)
                                  :headers         headers
                                  :body            (:body req)
                                  :as              :stream
                                  :throw-exceptions false})]
    {:status  (:status response)
     :headers (dissoc (:headers response) "transfer-encoding" "content-length")
     :body    (:body response)}))

(defn- service-id [uri]
  (when-let [[_ id] (re-find #"^/api/([^/]+)" uri)]
    (keyword id)))

(defn handler [req]
  (let [uri  (:uri req)
        sid  (service-id uri)
        svc  (and sid (get config/services sid))]
    (cond
      ;; Public config for frontend (service URLs for direct links, e.g.
      ;; Jellyfin item links and Pseudovision channel playback streams).
      (= uri "/api/config")
      (resp/response {:jellyfin-url     (:url config/jellyfin)
                      :pseudovision-url (get-in config/services [:pseudovision :url])})

      ;; Proxy to Jellyfin (images, metadata). Token is added server-side.
      (str/starts-with? uri "/api/jellyfin")
      (if (:url config/jellyfin)
        (proxy-to-jellyfin req)
        {:status 503 :body {:error "Jellyfin not configured (JELLYFIN_URL not set)"}})

      (nil? sid)
      ;; SPA catch-all: serve index.html for any non-API path so that
      ;; deep links and browser back/forward work with pushState routing.
      (-> (resp/response (slurp "public/index.html"))
          (resp/content-type "text/html"))

      (nil? svc)
      {:status 404 :body {:error (str "Unknown service: " (name sid))}}

      ;; Serve the rewritten OpenAPI spec to the frontend's martian.
      (re-matches #"/api/[^/]+/openapi\.json" uri)
      (resp/response (get @spec-cache sid))

      ;; Proxy everything else through with the auth token added.
      :else
      (proxy-to-backend sid svc req))))

(def app (wrap-json-response handler))

(defn -main [& _]
  (try
    (config/validate!)
    (preload-specs!)
    (catch Exception e
      (binding [*out* *err*]
        (println "Startup failed:" (.getMessage e)))
      (System/exit 1)))
  (let [port (Integer/parseInt (or (System/getenv "PORT") "3000"))]
    (http/run-server app {:port port})
    (println (str "BFF listening on port " port))))
