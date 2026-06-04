(ns marquee.views
  (:require [re-frame.core :as rf]
            [marquee.events :as events]
            [marquee.subs :as subs]
            [marquee.components.button :refer [button]]
            [marquee.pages.home :as home]
            [marquee.pages.about :as about]
            [marquee.pages.media :as media]
            [marquee.pages.media-detail :as media-detail]))

(def pages
  {:home         {:label "Home"  :view home/page :show-in-nav true}
   :about        {:label "About" :view about/page :show-in-nav true}
   :media        {:label "Media" :view media/page :show-in-nav true}
   :media-detail {:label "Media Detail" :view media-detail/page :show-in-nav false}})

(defn navbar []
  (let [active @(rf/subscribe [::subs/active-page])]
    [:nav {:class "flex items-center gap-1 border-b pb-4"}
     [:span {:class "mr-4 font-semibold"} "Marquee"]
     (for [[page {:keys [label show-in-nav]}] pages
           :when show-in-nav]
       ^{:key page}
       [button {:variant (if (= page active) :secondary :ghost)
                :size :sm
                :on-click #(rf/dispatch [::events/navigate page])}
        label])]))

(defn loading []
  [:div {:class "flex items-center justify-center h-screen text-muted-foreground"}
   "Loading…"])

(defn app []
  (let [active  @(rf/subscribe [::subs/active-page])
        ready?  @(rf/subscribe [::subs/api-ready?])
        view    (get-in pages [active :view])]
    (if-not ready?
      [loading]
      [:div {:class "container mx-auto max-w-2xl py-10"}
       [navbar]
       [:main {:class "py-8"}
        [view]]])))
