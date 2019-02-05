#! /bin/bash

planck ./vm-to-asm-compiler.cljs ./


echo "Simple Add"
CPUEmulator.sh ./StackArithmetic/SimpleAdd/SimpleAdd.tst

echo "Stack Test"
CPUEmulator.sh ./StackArithmetic/StackTest/StackTest.tst

echo "Basic Test"
CPUEmulator.sh ./MemoryAccess/BasicTest/BasicTest.tst

echo "Pointer Test"
CPUEmulator.sh ./MemoryAccess/PointerTest/PointerTest.tst

echo "Static Test"
CPUEmulator.sh ./MemoryAccess/StaticTest/StaticTest.tst

