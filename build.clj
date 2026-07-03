;; Marquee BFF uberjar build.
;;
;; Compiles the Clojure Backend-For-Frontend (HTTP entry point + API proxy
;; + SPA fallback) and bundles it together with the compiled SPA assets in
;; `public/` (JavaScript, CSS, index.html) so a single OCI image can serve
;; everything from one process on port 8080.

(ns build
  (:require [clojure.tools.build.api :as b])
  (:import (java.io File)
           (java.nio.file Files StandardCopyOption)))

(def class-dir "target/classes")
(def uber-file "target/marquee-server-standalone.jar")

(def basis
  (delay (b/create-basis {:project "deps.edn" :aliases [:server]})))

(defn- copy-tree
  "Recursively copy `src` directory into `dst`, preserving the top-level
  `src` name itself (unlike `b/copy-dir` which copies contents only)."
  [^File src ^File dst]
  (when (.isDirectory src)
    (when-not (.exists dst) (.mkdirs dst))
    (doseq [^File child (.listFiles src)]
      (let [target (File. dst (.getName child))]
        (if (.isDirectory child)
          (copy-tree child target)
          (do (.mkdirs (.getParentFile target))
              (Files/copy (.toPath child)
                          (.toPath target)
                          (into-array StandardCopyOption
                                      [StandardCopyOption/COPY_ATTRIBUTES
                                       StandardCopyOption/REPLACE_EXISTING]))))))))

(defn clean
  "Remove previous build artifacts."
  [_]
  (b/delete {:path "target"}))

(defn uber
  "Build a self-contained BFF uberjar that also embeds the SPA assets
  produced by the `site` derivation."
  [_]
  (clean nil)
  ;; Embed the compiled SPA (`public/` from the `site` flake output) at the
  ;; classpath root under a `public/` directory so
  ;; `ring.middleware.resource/wrap-resource "public"` can serve
  ;; `/js/main.js`, `/css/main.css`, etc. from the uberjar.
  (copy-tree (File. "public") (File. (str class-dir "/public")))
  (b/compile-clj {:basis     @basis
                  :src-dirs  ["src"]
                  :class-dir class-dir})
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis     @basis
           :exclude   [#"\.cljs$"]}))
