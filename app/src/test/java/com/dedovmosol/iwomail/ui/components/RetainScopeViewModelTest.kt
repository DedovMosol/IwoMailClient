package com.dedovmosol.iwomail.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class RetainScopeViewModelTest {

    @Test
    fun `send blocks a second send and a draft save`() {
        val vm = RetainScopeViewModel()
        assertThat(vm.tryBeginSend()).isTrue()
        assertThat(vm.tryBeginSend()).isFalse()
        assertThat(vm.tryBeginSave()).isFalse()
        vm.sending = false
        assertThat(vm.tryBeginSave()).isTrue()
        assertThat(vm.tryBeginSend()).isFalse()
    }

    @Test
    fun `leaving blocks send and hides the editor`() {
        val vm = RetainScopeViewModel()
        vm.markLeaving()
        assertThat(vm.leaving).isTrue()
        assertThat(vm.hideEditor).isTrue()
        assertThat(vm.tryBeginSend()).isFalse()
        assertThat(vm.tryBeginSave()).isFalse()
    }

    @Test
    fun `check back blocks send until released`() {
        val vm = RetainScopeViewModel()
        assertThat(vm.tryBeginCheck()).isTrue()
        assertThat(vm.tryBeginSend()).isFalse()
        assertThat(vm.tryBeginSave()).isFalse()
        vm.checkingBack = false
        vm.askDiscard = true
        assertThat(vm.tryBeginSend()).isFalse()
        vm.askDiscard = false
        assertThat(vm.tryBeginSend()).isTrue()
    }
}
