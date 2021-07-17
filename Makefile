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
BUILD_DIR = $(abspath ./build)
CSRC_DIR = $(abspath ./src/test/resources/csrc)
SCALA_SRC = $(shell find ./src/main/scala -name "*.scala")

all_tests: f32_add_tests f32_sub_tests f64_add_tests f64_sub_tests

define test_template

$(1)_emu = $$(BUILD_DIR)/$(2)_$(3)/$(2).emu
$(1)_v = $$(BUILD_DIR)/$(2)_$(3)/$(2).v

$$($(1)_v): $$(SCALA_SRC)
	mill fudian.runMain fudian.Generator --fu $(2) --ftype $(3) --full-stacktrace -td $$(@D)

$$($(1)_emu): $$($(1)_v) $$(CSRC_DIR)/$(2)_Test.cpp
	verilator --cc --exe $$^ -Mdir $$(@D) -o $$@ --build

$(1)_test_rnear_even: $$($(1)_emu)
	$$(TEST_FLOAT_GEN) $(1) -tininessafter -rnear_even -level 2 | $$< -rnear_even $(4)

$(1)_test_rminMag: $$($(1)_emu)
	$$(TEST_FLOAT_GEN) $(1) -tininessafter -rminMag -level 2 | $$< -rminMag $(4)

$(1)_test_rmin: $$($(1)_emu)
	$$(TEST_FLOAT_GEN) $(1) -tininessafter -rmin -level 2 | $$< -rmin $(4)

$(1)_test_rmax: $$($(1)_emu)
	$$(TEST_FLOAT_GEN) $(1) -tininessafter -rmax -level 2 | $$< -rmax $(4)

$(1)_test_rnear_maxMag: $$($(1)_emu)
	$$(TEST_FLOAT_GEN) $(1) -tininessafter -rnear_maxMag -level 2 | $$< -rnear_maxMag $(4)

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
