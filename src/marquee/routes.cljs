(ns marquee.routes)

(def page->path
  {:home             "/"
   :about            "/about"
   :media            "/media"
   :api-docs         "/api-docs"
   :schedule-grid    "/schedule"
   :channel-schedule "/schedule/channel"})

(defn media-detail-path [media-id]
  (str "/media/" media-id))

(defn channel-path [channel-id]
  (str "/schedule/channel/" channel-id))

(defn parse-path [path]
  (case path
    "/"          {:page :home}
    "/about"     {:page :about}
    "/media"     {:page :media}
    "/api-docs"  {:page :api-docs}
    "/schedule"  {:page :schedule-grid}
    (or (when-let [[_ id] (re-matches #"/media/(.+)" path)]
          {:page :media-detail :media-id id})
        (when-let [[_ id] (re-matches #"/schedule/channel/(.+)" path)]
          {:page :channel-schedule :channel-id (js/parseInt id)}))))
