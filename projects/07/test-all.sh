#! /bin/bash

echo "Simple Add"
rm -f ./StackArithmetic/SimpleAdd/SimpleAdd.asm
planck ./vm-to-asm-compiler.cljs ./StackArithmetic/SimpleAdd/SimpleAdd.vm
CPUEmulator.sh ./StackArithmetic/SimpleAdd/SimpleAdd.tst

echo "Stack Test"
rm -f ./StackArithmetic/StackTest/StackTest.asm
planck ./vm-to-asm-compiler.cljs ./StackArithmetic/StackTest/StackTest.vm
CPUEmulator.sh ./StackArithmetic/StackTest/StackTest.tst

echo "Basic Test"
rm -f  ./MemoryAccess/BasicTest/BasicTest.asm
planck ./vm-to-asm-compiler.cljs ./MemoryAccess/BasicTest/BasicTest.vm
CPUEmulator.sh ./MemoryAccess/BasicTest/BasicTest.tst

echo "Pointer Test"
rm -f ./MemoryAccess/PointerTest/PointerTest.asm
planck ./vm-to-asm-compiler.cljs ./MemoryAccess/PointerTest/PointerTest.vm
CPUEmulator.sh ./MemoryAccess/PointerTest/PointerTest.tst

echo "Static Test"
rm -f ./MemoryAccess/StaticTest/StaticTest.asm
planck ./vm-to-asm-compiler.cljs ./MemoryAccess/StaticTest/StaticTest.vm
CPUEmulator.sh ./MemoryAccess/StaticTest/StaticTest.tst

