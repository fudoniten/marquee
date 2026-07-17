(ns marquee.routes
  (:require [clojure.string :as str]))

(def page->path
  {:home              "/"
   :media             "/media"
   :browse            "/browse"
   :api-docs          "/api-docs"
   :schedule-grid     "/schedule"
   :channel-schedule  "/schedule/channel"
   :jobs              "/jobs"
   :collections       "/collections"})

(defn media-detail-path [media-id]
  (str "/media/" media-id))

(defn grout-detail-path [id]
  (str "/media/grout/" id))

(defn channel-path [channel-id]
  (str "/schedule/channel/" channel-id))

(defn collection-path [collection-id]
  (str "/collections/" collection-id))

(defn browse-path
  ([facet] (str "/browse/" (name facet)))
  ([facet selection]
   (str "/browse/" (name facet) "/" (js/encodeURIComponent selection))))

;; --- Query-string helpers ---------------------------------------------------
;; The Media and Grout list views carry state (selected library, page, filter;
;; grout collection, kind, page, filter) that the URL needs to reflect so a
;; reload / share / browser-Back lands on the same view. That state rides in the
;; query string; the leaf path stays `/media` or `/media/grout`.

(defn- query-string
  "Build a `?a=1&b=2` string from `pairs` ([key value] seq), dropping nil/blank
   values and url-encoding each value. Returns \"\" when nothing survives."
  [pairs]
  (let [items (for [[k v] pairs
                    :when (and (some? v)
                               (not (and (string? v) (str/blank? v))))]
                (str (name k) "=" (js/encodeURIComponent (str v))))]
    (if (seq items) (str "?" (str/join "&" items)) "")))

(defn media-library-path
  "URL for the library view: /media?library=<id>&page=<n>&q=<filter>. `page` is
   omitted when 1 and `q` when blank, to keep the common URL clean."
  [{:keys [library-id page filter]}]
  (str "/media"
       (query-string [[:library library-id]
                      [:page (when (and page (> page 1)) page)]
                      [:q    filter]])))

(defn grout-path
  "URL for the Grout view: /media/grout?collection=<tag>&kind=<k>&page=<n>&q=<f>.
   With no collection this is the collections index."
  [{:keys [collection kind page filter]}]
  (str "/media/grout"
       (query-string [[:collection collection]
                      [:kind       kind]
                      [:page       (when (and page (> page 1)) page)]
                      [:q          filter]])))

(defn- parse-query
  "Parse a raw query string (no leading `?`) into a {string → string} map."
  [q]
  (if (str/blank? q)
    {}
    (->> (str/split q #"&")
         (map #(str/split % #"=" 2))
         (map (fn [[k v]] [k (js/decodeURIComponent (or v ""))]))
         (into {}))))

(defn- parse-int* [s]
  (when s
    (let [n (js/parseInt s 10)]
      (when-not (js/isNaN n) n))))

(defn parse-path
  "Parse a URL (pathname, optionally with a `?query`) into a view descriptor."
  [raw]
  (let [raw    (or raw "")
        qidx   (str/index-of raw "?")
        path   (if qidx (subs raw 0 qidx) raw)
        params (parse-query (when qidx (subs raw (inc qidx))))]
    (case path
      "/"            {:page :home}
      "/media"       (cond-> {:page :media :source :library}
                       (params "library") (assoc :library-id (parse-int* (params "library")))
                       (params "page")    (assoc :page-num   (parse-int* (params "page")))
                       (params "q")       (assoc :filter     (params "q")))
      "/media/grout" (cond-> {:page :media :source :grout}
                       (params "collection") (assoc :collection (params "collection"))
                       (params "kind")       (assoc :kind       (params "kind"))
                       (params "page")       (assoc :grout-page (parse-int* (params "page")))
                       (params "q")          (assoc :filter     (params "q")))
      "/browse"            {:page :browse :facet :tags}
      "/browse/tags"       {:page :browse :facet :tags}
      "/browse/dimensions" {:page :browse :facet :dimensions}
      "/api-docs"  {:page :api-docs}
      "/schedule"  {:page :schedule-grid}
      (or (when-let [[_ id] (re-matches #"/media/grout/(.+)" path)]
            {:page :grout-detail :media-id id})
          (when-let [[_ id] (re-matches #"/media/(.+)" path)]
            {:page :media-detail :media-id id})
          (when-let [[_ facet sel] (re-matches #"/browse/(tags|dimensions)(?:/(.+))?" path)]
            {:page      :browse
             :facet     (keyword facet)
             :selection (when sel (js/decodeURIComponent sel))})
          (when-let [[_ id] (re-matches #"/schedule/channel/(.+)" path)]
            {:page :channel-schedule :channel-id (js/parseInt id)})
          (when (= path "/collections")
            {:page :collections})
          (when-let [[_ id] (re-matches #"/collections/(.+)" path)]
            {:page :collection-detail :collection-id id})
          (when (= path "/jobs")
            {:page :jobs})))))
