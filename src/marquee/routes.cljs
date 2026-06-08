(ns marquee.routes)

(def page->path
  {:home     "/"
   :about    "/about"
   :media    "/media"
   :api-docs "/api-docs"})

(defn media-detail-path [media-id]
  (str "/media/" media-id))

(defn parse-path [path]
  (case path
    "/"         {:page :home}
    "/about"    {:page :about}
    "/media"    {:page :media}
    "/api-docs" {:page :api-docs}
    (when-let [[_ id] (re-matches #"/media/(.+)" path)]
      {:page :media-detail :media-id id})))
