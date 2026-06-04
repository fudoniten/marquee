(ns marquee.pages.media
  (:require [re-frame.core :as rf]
            [marquee.events :as events]
            [marquee.subs :as subs]
            [marquee.components.button :refer [button]]
            [marquee.components.card :refer [card card-header card-title
                                             card-description card-content
                                             card-footer]]))

(defn media-item-card [{:keys [id title description kind duration]}]
  [card {:class "mb-4"}
   [card-header {}
    [card-title {} (or title (str "Item #" id))]
    (when kind
      [card-description {} (str "Type: " kind)])]
   [card-content {}
    (when description
      [:p {:class "text-sm text-muted-foreground mb-2"} description])
    (when duration
      [:p {:class "text-xs text-muted-foreground"}
       (str "Duration: " (js/Math.floor (/ duration 60)) " min")])]
   [card-footer {}
    [button {:size :sm
             :variant :outline
             :on-click #(rf/dispatch [::events/navigate-to-media-detail id])}
     "View Details"]]])

(defn library-section [{:keys [id name]}]
  (let [items @(rf/subscribe [::subs/library-items id])]
    [:div {:class "mb-8"}
     [:h2 {:class "text-2xl font-semibold mb-4"} name]
     (if (nil? items)
       [:p {:class "text-muted-foreground"} "Loading..."]
       (if (empty? items)
         [:p {:class "text-muted-foreground"} "No items found."]
         [:div {:class "grid gap-4"}
          (for [item items]
            ^{:key (:id item)}
            [media-item-card item])]))]))

(defn page []
  (let [libraries @(rf/subscribe [::subs/media-libraries])]
    [:div {:class "space-y-6"}
     [:div
      [:h1 {:class "text-3xl font-bold tracking-tight"} "Media"]
      [:p {:class "text-muted-foreground"}
       "Browse media items from Pseudovision."]]
     
     (when (nil? libraries)
       [:p {:class "text-muted-foreground"} "Loading libraries..."])
     
     (when (and (not (nil? libraries)) (empty? libraries))
       [:p {:class "text-muted-foreground"} "No libraries found."])
     
     (when (seq libraries)
       [:div
        (for [library libraries]
          ^{:key (:id library)}
          [library-section library])])]))
