(ns marquee.components.button
  "shadcn/ui Button, as a Reagent component. Uses class-variance-authority
  with the same variant/size definitions shadcn ships."
  (:require ["class-variance-authority" :refer [cva]]
            [marquee.lib.utils :refer [cn]]))

(def button-variants
  (cva
   "inline-flex items-center justify-center whitespace-nowrap rounded-md text-sm font-medium ring-offset-background transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 disabled:pointer-events-none disabled:opacity-50"
   (clj->js
    {:variants
     {:variant
      {:default     "bg-primary text-primary-foreground hover:bg-primary/90"
       :destructive "bg-destructive text-destructive-foreground hover:bg-destructive/90"
       :outline     "border border-input bg-background hover:bg-accent hover:text-accent-foreground"
       :secondary   "bg-secondary text-secondary-foreground hover:bg-secondary/80"
       :ghost       "hover:bg-accent hover:text-accent-foreground"
       :link        "text-primary underline-offset-4 hover:underline"}
      :size
      {:default "h-10 px-4 py-2"
       :sm      "h-9 rounded-md px-3"
       :lg      "h-11 rounded-md px-8"
       :icon    "h-10 w-10"}}
     :defaultVariants {:variant "default" :size "default"}})))

(defn button
  "Render a styled button.
  `props` may include :variant, :size, :class, and any other HTML attrs
  (e.g. :on-click). Remaining args are children."
  [{:keys [variant size class] :as props} & children]
  (let [attrs (-> (dissoc props :variant :size :class)
                  (assoc :class (cn (button-variants
                                     #js {:variant (some-> variant name)
                                          :size    (some-> size name)})
                                    class)))]
    (into [:button attrs] children)))
