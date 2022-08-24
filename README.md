# fudian - High Performance IEEE-754 Floating-Point Unit

## Function units implemented in fudian

- Two-path Floating-point Adder
- Floating-point Multiplier
- Cascade FMA
- Float -> Int Converter
- Int -> Float Converter
- Float -> Float Converter
- SRT16 Floating-point Divider / SRT4 Floating-point Square Root Unit

## Unit testing

Run `make init` to init git submodules

Then run `make berkeley-testfloat-3/build/Linux-x86_64-GCC/testfloat_gen` to compile Softfloat and Testfloat

Run `make all_tests` to run all unit tests.

To run a specific test, see Makefile for more details.
