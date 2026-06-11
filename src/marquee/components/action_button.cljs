(ns marquee.components.action-button
  "Button that tracks a re-frame action-key for loading/success/error state.
   Shared by the task launch panels on the Media, Schedule and Jobs pages."
  (:require [re-frame.core :as rf]
            [marquee.subs :as subs]
            [marquee.components.button :refer [button]]))

(defn action-btn
  [{:keys [action-key label on-click variant size disabled]}]
  (let [{:keys [status message]} @(rf/subscribe [::subs/action-state action-key])
        loading? (= status :loading)]
    [:div {:class "flex flex-col items-start gap-0.5"}
     [button {:size     (or size :sm)
              :variant  (case status
                          :error   :destructive
                          :success :secondary
                          (or variant :outline))
              :disabled (or loading? disabled)
              :on-click on-click}
      (case status
        :loading (str label "…")
        :success (or message label)
        :error   "Error"
        label)]
     (when (= status :error)
       [:p {:class "text-xs text-destructive max-w-48 truncate" :title message} message])]))
