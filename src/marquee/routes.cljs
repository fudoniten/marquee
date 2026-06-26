(ns marquee.routes)

(def page->path
  {:home              "/"
   :about             "/about"
   :media             "/media"
   :browse            "/browse"
   :api-docs          "/api-docs"
   :schedule-grid     "/schedule"
   :channel-schedule  "/schedule/channel"
   :jobs              "/jobs"
   :collections       "/collections"})

(defn media-detail-path [media-id]
  (str "/media/" media-id))

(defn channel-path [channel-id]
  (str "/schedule/channel/" channel-id))

(defn collection-path [collection-id]
  (str "/collections/" collection-id))

(defn browse-path
  ([facet] (str "/browse/" (name facet)))
  ([facet selection]
   (str "/browse/" (name facet) "/" (js/encodeURIComponent selection))))

(defn parse-path [path]
  (case path
    "/"          {:page :home}
    "/about"     {:page :about}
    "/media"     {:page :media}
     "/browse"    {:page :browse :facet :tags}
     "/browse/tags"       {:page :browse :facet :tags}
     "/browse/dimensions" {:page :browse :facet :dimensions}
    "/api-docs"  {:page :api-docs}
    "/schedule"  {:page :schedule-grid}
     (or (when-let [[_ id] (re-matches #"/media/(.+)" path)]
           {:page :media-detail :media-id id})
         (when-let [[_ facet sel] (re-matches #"/browse/(tags|genres|channels|dimensions)(?:/(.+))?" path)]
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
          {:page :jobs}))))
