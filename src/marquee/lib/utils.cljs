(ns marquee.lib.utils
  "The shadcn `cn` helper: merge class names with clsx, then resolve
  conflicting Tailwind classes with tailwind-merge."
  (:require ["clsx" :refer [clsx]]
            ["tailwind-merge" :refer [twMerge]]))

(defn cn [& inputs]
  (twMerge (clsx (clj->js inputs))))
