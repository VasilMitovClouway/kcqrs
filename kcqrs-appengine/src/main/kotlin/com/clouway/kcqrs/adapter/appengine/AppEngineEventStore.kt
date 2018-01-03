package com.clouway.kcqrs.adapter.appengine

import com.clouway.kcqrs.core.*
import com.google.appengine.api.datastore.*
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import java.util.*

/**
 * @author Miroslav Genov (miroslav.genov@clouway.com)
 */
class AppEngineEventStore(private val kind: String = "EventStore") : EventStore {
    private val gson = Gson()

    /**
     * Property name for the list of events in storage
     */
    private val eventsProperty = "Events"

    override fun saveEvents(aggregateId: UUID, expectedVersion: Int, events: Iterable<Event>) {
        var transaction: Transaction? = null

        try {
            val dataStore = DatastoreServiceFactory.getDatastoreService()
            transaction = dataStore.beginTransaction()

            val key = KeyFactory.createKey(kind, aggregateId.toString())
            var entity: Entity
            var currentVersion: Long = 0

            var entityEvents = mutableListOf<String>()
            try {
                entity = dataStore.get(transaction, key)

                @Suppress("UNCHECKED_CAST")
                entityEvents = entity.getProperty(eventsProperty) as MutableList<String>
                currentVersion = entityEvents.size.toLong()

                //if the current version is different than what was hydrated during the state change then we know we
                //have an event collision. This is a very simple approach and more "business knowledge" can be added
                //here to handle scenarios where the versions may be different but the state change can still occur.
                if (currentVersion != expectedVersion.toLong()) {
                    throw EventCollisionException(aggregateId, expectedVersion)
                }

            } catch (e: EntityNotFoundException) {
                // Not a problem, just continue on. It is a new aggregate
                entity = Entity(kind, aggregateId.toString())
            }

            //convert all of the new events to json for storage
            for (event in events) {

                //increment the current version
                currentVersion++

                val eventJson = gson.toJson(event)
                val kind = event::class.java.simpleName

                val newEvent = EventModel(kind, eventJson, currentVersion, Date().time)

                val json = gson.toJson(newEvent)
                entityEvents.add(json)
            }

            entity.setUnindexedProperty(eventsProperty, entityEvents)

            dataStore.put(entity)
            transaction.commit()

        } finally {
            if (transaction != null && transaction.isActive) {
                transaction.rollback()
            }
        }
    }

    override fun revertEvents(aggregateId: UUID, events: Iterable<Event>) {
        val dataStore = DatastoreServiceFactory.getDatastoreService()

        val target = listOf(events)

        val key = KeyFactory.createKey(kind, aggregateId.toString())
        val entity = dataStore.get(key)

        @Suppress("UNCHECKED_CAST")
        val entityEvents = entity.getProperty(eventsProperty) as MutableList<String>

        val lastItemIndex = entityEvents.size - target.size

        val newEvents = entityEvents.filterIndexed { index, _ ->  index <= lastItemIndex }

        entity.setUnindexedProperty(eventsProperty, newEvents)
        dataStore.put(entity)

    }

    override fun <T> getEvents(aggregateId: UUID, aggregateType: Class<T>): Iterable<Event> {
        val dataStore = DatastoreServiceFactory.getDatastoreService()

        val key = KeyFactory.createKey(kind, aggregateId.toString())
        val entity: Entity

        try {
            entity = dataStore.get(key)
        } catch (e: EntityNotFoundException) {
            throw AggregateNotFoundException(aggregateId)
        }

        return hydrateEvents(entity, aggregateType)
    }

    /**
     * Loop through all of the events and deserialize the json into their respective types
     *
     * @param entity
     * @return
     * @throws HydrationException
     */
    @Throws(HydrationException::class)
    private fun <T> hydrateEvents(entity: Entity, aggregateType: Class<T>): List<Event> {

        @Suppress("UNCHECKED_CAST")
        val events = entity.getProperty(eventsProperty) as List<String>
        val history = mutableListOf<Event>()

        val methods = aggregateType.declaredMethods
        val eventTypes = mutableMapOf<String, String>()
        methods.forEach {
            if (it.name === "apply") {
                it.parameters.forEach {
                    eventTypes[it.type.simpleName] = it.type.name
                }
            }
        }

        events
                .map { gson.fromJson(it, EventModel::class.java) }
                .forEach {
                    try {
                        val event = gson.fromJson(it.json, Class.forName(eventTypes[it.kind])) as Event
                        history.add(event)
                    } catch (e: JsonSyntaxException) {
                        /*
                         * Throw a hydration exception along with the aggregate Id and the message
                        */
                        throw HydrationException(UUID.fromString(entity.key.toString()), e.message)
                    } catch (e: ClassNotFoundException) {
                        throw HydrationException(UUID.fromString(entity.key.toString()), e.message)
                    }
                }

        return history
    }
}

data class EventModel(@JvmField val kind: String, @JvmField val json: String, @JvmField val version: Long, @JvmField val timestamp: Long)