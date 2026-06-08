(ns marquee.server.core
  (:require [org.httpkit.server :as http]
            [ring.middleware.json :refer [wrap-json-response]]
            [ring.util.response :as resp]
            [clj-http.client :as client]
            [marquee.server.config :as config]
            [clojure.string :as str]))

;; Holds fetched+rewritten specs keyed by service-id.
(defonce spec-cache (atom {}))

(defn- fetch-and-cache-spec! [service-id {:keys [url spec-path] :or {spec-path "/openapi.json"}}]
  (try
    (let [response (client/get (str url spec-path) {:as :json :throw-exceptions false})]
      (when (= 200 (:status response))
        (let [spec      (:body response)
              base-path (str "/api/" (name service-id))
              ;; Rewrite the server URL so the frontend's martian routes through the BFF.
              rewritten (if (get spec "openapi")
                          (assoc spec "servers" [{"url" base-path}])           ; OpenAPI 3.x
                          (assoc spec "basePath" base-path "host" "" "schemes" []))]  ; Swagger 2.0
          (swap! spec-cache assoc service-id rewritten)
          rewritten)))
    (catch Exception e
      (println "Failed to fetch spec for" service-id ":" (.getMessage e))
      nil)))

(defn- get-spec [service-id service]
  (or (get @spec-cache service-id)
      (fetch-and-cache-spec! service-id service)))

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

(defn- service-id [uri]
  (when-let [[_ id] (re-find #"^/api/([^/]+)" uri)]
    (keyword id)))

(defn handler [req]
  (let [uri  (:uri req)
        sid  (service-id uri)
        svc  (and sid (get config/services sid))]
    (cond
      (nil? sid)
      {:status 404 :body {:error "Not found"}}

      (nil? svc)
      {:status 404 :body {:error (str "Unknown service: " (name sid))}}

      ;; Serve the rewritten OpenAPI spec to the frontend's martian.
      (re-matches #"/api/[^/]+/openapi\.json" uri)
      (if-let [spec (get-spec sid svc)]
        (resp/response spec)
        {:status 502 :body {:error "Could not fetch spec from backend"}})

      ;; Proxy everything else through with the auth token added.
      :else
      (proxy-to-backend sid svc req))))

(def app (wrap-json-response handler))

(defn -main [& _]
  (config/validate!)
  (let [port (Integer/parseInt (or (System/getenv "PORT") "3000"))]
    (http/run-server app {:port port})
    (println (str "BFF listening on port " port))))
