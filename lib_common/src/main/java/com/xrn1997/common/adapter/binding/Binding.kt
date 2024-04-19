package com.xrn1997.common.adapter.binding

/**
 * BindingAction
 * @author xrn1997
 */
interface BindingAction {
    fun call()
}

/**
 * BindingCommand
 */
class BindingCommand(private val execute: BindingAction?) {
    fun execute() {
        execute?.call()
    }
}
