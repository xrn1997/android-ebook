package com.xrn1997.common.event

/**
 * BaseEvent
 * @author xrn1997
 */
open class BaseEvent<T>(var msg: String? = null, var data: T? = null)

/**
 * EventBus BaseActivityEvent
 * @author xrn1997
 */
class BaseActivityEvent<T>(msg: String) : BaseEvent<T>(msg)

/**
 * EventBus BaseComposeActivityEvent
 * @author xrn1997
 */
class BaseComposeActivityEvent<T>(msg: String) : BaseEvent<T>(msg)
/**
 * EventBus BaseFragmentEvent
 * @author xrn1997
 */
class BaseFragmentEvent<T>(msg: String) : BaseEvent<T>(msg)