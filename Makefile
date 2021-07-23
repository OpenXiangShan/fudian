compile:
	mill -i fudian.compile

bsp:
	mill -i mill.bsp.BSP/install

clean:
	rm -rf ./build

reformat:
	mill -i __.reformat

checkformat:
	mill -i __.checkFormat 

berkeley-softfloat-3/build/Linux-x86_64-GCC/softfloat.a: berkeley-softfloat-3/.git
	$(MAKE) -C berkeley-softfloat-3/build/Linux-x86_64-GCC SPECIALIZE_TYPE=RISCV

berkeley-testfloat-3/build/Linux-x86_64-GCC/testfloat_gen: berkeley-testfloat-3/.git \
                                                           berkeley-softfloat-3/build/Linux-x86_64-GCC/softfloat.a
	$(MAKE) -C berkeley-testfloat-3/build/Linux-x86_64-GCC SPECIALIZE_TYPE=RISCV

TEST_FLOAT_GEN = berkeley-testfloat-3/build/Linux-x86_64-GCC/testfloat_gen
SEED ?= $(shell shuf -i 1-100000 -n 1)
TEST_FLOAT_OPTS = -tininessafter -exact -level 2 -seed $(SEED) -forever
BUILD_DIR = $(abspath ./build)
CSRC_DIR = $(abspath ./src/test/resources/csrc)
SCALA_SRC = $(shell find ./src/main/scala -name "*.scala")

fadd_tests: f32_add_tests f64_add_tests f32_sub_tests f64_sub_tests

fn_to_int32_tests: f32_to_ui32_tests f32_to_i32_tests f64_to_ui32_tests f64_to_i32_tests
fn_to_int64_tests: f32_to_ui64_tests f32_to_i64_tests f64_to_ui64_tests f64_to_i64_tests
fp_to_int_tests: fn_to_int32_tests fn_to_int64_tests

all_tests: fadd_tests fp_to_int_tests

define test_template

$(1)_emu = $$(BUILD_DIR)/$(2)_$(3)/$(2).emu
$(1)_v = $$(BUILD_DIR)/$(2)_$(3)/$(2).v

$$($(1)_v): $$(SCALA_SRC)
	mill fudian.runMain fudian.Generator --fu $(2) --ftype $(3) --full-stacktrace -td $$(@D)

$$($(1)_emu): $$($(1)_v) $$(CSRC_DIR)/$(2)_Test.cpp
	verilator --cc --exe $$^ -Mdir $$(@D) -o $$@ --build

$(1)_test_rnear_even: $$($(1)_emu)
	$$(TEST_FLOAT_GEN) $$(TEST_FLOAT_OPTS) $(1) -rnear_even | $$< -rnear_even $(4)

$(1)_test_rminMag: $$($(1)_emu)
	$$(TEST_FLOAT_GEN) $$(TEST_FLOAT_OPTS) $(1) -rminMag | $$< -rminMag $(4)

$(1)_test_rmin: $$($(1)_emu)
	$$(TEST_FLOAT_GEN) $$(TEST_FLOAT_OPTS) $(1) -rmin | $$< -rmin $(4)

$(1)_test_rmax: $$($(1)_emu)
	$$(TEST_FLOAT_GEN) $$(TEST_FLOAT_OPTS) $(1) -rmax | $$< -rmax $(4)

$(1)_test_rnear_maxMag: $$($(1)_emu)
	$$(TEST_FLOAT_GEN) $$(TEST_FLOAT_OPTS) $(1) -rnear_maxMag | $$< -rnear_maxMag $(4)

$(1)_tests: $(1)_test_rnear_even \
			$(1)_test_rminMag \
			$(1)_test_rmin \
			$(1)_test_rmax \
			$(1)_test_rnear_maxMag
endef

$(eval $(call test_template,f32_add,FADD,32,add))
$(eval $(call test_template,f32_sub,FADD,32,sub))
$(eval $(call test_template,f64_add,FADD,64,add))
$(eval $(call test_template,f64_sub,FADD,64,sub))

$(eval $(call test_template,f64_to_ui64,FPToInt,64,f_to_ui64))
$(eval $(call test_template,f64_to_i64,FPToInt,64,f_to_i64))
$(eval $(call test_template,f32_to_ui64,FPToInt,32,f_to_ui64))
$(eval $(call test_template,f32_to_i64,FPToInt,32,f_to_i64))

$(eval $(call test_template,f64_to_ui32,FPToInt,64,f_to_ui32))
$(eval $(call test_template,f64_to_i32,FPToInt,64,f_to_i32))
$(eval $(call test_template,f32_to_ui32,FPToInt,32,f_to_ui32))
$(eval $(call test_template,f32_to_i32,FPToInt,32,f_to_i32))
