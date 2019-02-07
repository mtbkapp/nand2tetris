(ns assembler-tests
  (:require [assembler :as a]
            [clojure.string :as string]
            [clojure.test :as t]
            [planck.core :as p]
            [planck.io :as io]
            [planck.shell :as sh]))


(defn assembled? 
  [file]
  (let [dir (string/replace (str file) #"([^/]*)\.hack$" "")]
    (contains? (into #{} (io/list-files dir))
               (io/file file))))


(defn fetch-expected-code 
  [asm-file]
  (let [asm-file (str asm-file)
        hack-file (a/hack-file asm-file)]
    (when-not (assembled? hack-file)
      (prn (sh/sh "../../tools/Assembler.sh" asm-file)))
    (-> hack-file
        (p/slurp)
        (string/split-lines))))


(t/deftest test-parse-line 
  (t/is (= [:itype/a :vtype/sym "LOOP"] (a/parse-line "@LOOP")))
  (t/is (= [:itype/a :vtype/addr 132] (a/parse-line "@132")))
  (t/is (= [:itype/label "LOOP"] (a/parse-line "(LOOP)")))
  (t/is (= [:itype/label "L"] (a/parse-line "(L)")))
  (t/is (= [:itype/c "D" "M" nil] (a/parse-line "D=M")))
  (t/is (= [:itype/c "D" "M+1" nil] (a/parse-line "D=M+1")))
  (t/is (= [:itype/c "AMD" "M+1" nil] (a/parse-line "AMD=M+1")))
  (t/is (= [:itype/c "AMD" "!A" nil] (a/parse-line "AMD=!A"))))


(t/deftest test-first-pass-xform
  (let [program ["// blah blah"
                 ""
                 "    // junk"
                 "@var"
                 "@256 // setup stack pointer"
                 "  D=A "
                 "  @SP"
                 "  M=D"
                 "  // done setting up stack pointer"]]
    (t/is (= [[:itype/a :vtype/sym "var"]
              [:itype/a :vtype/addr 256]
              [:itype/c "D" "A" nil]
              [:itype/a :vtype/sym "SP"]
              [:itype/c "M" "D" nil]]
             (into [] a/first-pass-xform program)))))


(t/deftest test-first-pass
  (let [prg ["@2"    ;0
             "@A"    ;1
             "@var1" ;2
             "@var2" ;3 
             "M=M+1" ;4
             "(blah)"
             "A=M"   ;5
             "(A)"
             "A=M"   ;6
             "(B)"
             "A=M"]  ;7
        {:keys [rom-addr program sym-tab]} (a/first-pass prg)]
    (t/is (= 8 rom-addr))
    (t/is (= 8 (count program)))
    (t/is (= 5 (get sym-tab "blah" :not-found)))
    (t/is (= 6 (get sym-tab "A" :not-found)))  
    (t/is (= 7 (get sym-tab "B" :not-found)))
    (t/is (= :not-found (get sym-tab "var1" :not-found)))
    (t/is (= :not-found (get sym-tab "var2" :not-found)))
    (t/is (= [[:itype/a :vtype/addr 2]
              [:itype/a :vtype/sym "A"]
              [:itype/a :vtype/sym "var1"]
              [:itype/a :vtype/sym "var2"]
              [:itype/c "M" "M+1" nil]
              [:itype/c "A" "M" nil]
              [:itype/c "A" "M" nil]
              [:itype/c "A" "M" nil]]
             program))))


(t/deftest test-second-pass
  (let [prg [[:itype/a :vtype/sym "SP"] 
             [:itype/a :vtype/sym "LOOP"]
             [:itype/a :vtype/addr 1234]
             [:itype/c "D" "M" nil]
             [:itype/a :vtype/sym "counter"]
             [:itype/c "D" "M" nil]
             [:itype/c "D" "M" nil]
             [:itype/a :vtype/sym "counter"]
             [:itype/a :vtype/sym "max"]]
        expected ["0000000000000000"
                  "0000000000001111"
                  "0000010011010010"
                  "1111110000010000"
                  "0000000000010000"
                  "1111110000010000"
                  "1111110000010000"
                  "0000000000010000"
                  "0000000000010001"]
        sym-tab (assoc a/base-symbol-table "LOOP" 15)
        {:keys [program sym-tab var-addr]} (a/second-pass {:program prg :sym-tab sym-tab})]
    (t/is (= (count prg) (count program)))
    (t/is (= (+ a/var-start-addr 2) var-addr))
    (t/is (= a/var-start-addr (get sym-tab "counter" :not-found)))
    (t/is (= (inc a/var-start-addr) (get sym-tab "max" :not-found)))
    (dotimes [i (count program)]
      (t/testing (str "Line: " (inc i))
        (t/is (= (nth expected i) (nth program i)))))))


(defn compare-programs
  [asm-file]
  (let [expected-code (fetch-expected-code asm-file)
        actual-code (into [] (a/compile-program (string/split-lines (p/slurp asm-file))))]
    (t/testing (str "File: " asm-file)
      (t/is (= (count expected-code) (count actual-code)))
      (dotimes [i (count expected-code)]
        (t/testing (str "Line: " (inc i))
          (t/is (= (nth expected-code i) (nth actual-code i))))))))


(t/deftest test-compile-program
  (t/testing "Math programs"
    (compare-programs "add/Add.asm")
    (compare-programs "max/MaxL.asm")
    (compare-programs "max/Max.asm"))
  (t/testing "Rect programs"
    (compare-programs "rect/RectL.asm")
    (compare-programs "rect/Rect.asm"))
  (t/testing "Pong programs"
    (compare-programs "pong/PongL-no-comment.asm")
    (compare-programs "pong/PongL.asm")
    (compare-programs "pong/Pong-no-comment.asm")
    (compare-programs "pong/Pong.asm")))


(defn -main
  [& args]
  (t/run-tests))

