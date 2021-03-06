package com.clouway.kcqrs.core

import java.util.*

/**
 * @author Miroslav Genov (miroslav.genov@clouway.com)
 */
class AggregateNotFoundException(aggregateId: UUID) : AggregateException(aggregateId, "Aggregate was not found")