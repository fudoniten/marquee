(ns marquee.server.dev
  (:require [clojure.string :as str])
  (:import [shadow.http.server HttpRequest]))

;; Used by shadow-cljs :proxy-predicate.
;; Routes /api/* and SPA navigation paths (no file extension) to the BFF.
;; Static assets like /js/main.js are served directly from :root.
(defn api-or-spa-request? [^HttpRequest req _config]
  (let [path (.getRequestPath req)]
    (or (str/starts-with? path "/api/")
        (not (re-find #"\.[a-zA-Z0-9]+$" path)))))
