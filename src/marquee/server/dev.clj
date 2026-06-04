(ns marquee.server.dev
  (:require [clojure.string :as str])
  (:import [shadow.http.server HttpRequest]))

;; Used by shadow-cljs :proxy-predicate to route only /api/* to the BFF.
(defn api-request? [^HttpRequest req _config]
  (str/starts-with? (.getRequestPath req) "/api"))
