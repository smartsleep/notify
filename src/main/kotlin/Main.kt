import dk.ku.sund.notify.database.db
import dk.ku.sund.notify.model.Attendee
import kotlinx.coroutines.reactive.consumeEach
import kotlinx.coroutines.runBlocking
import com.google.firebase.messaging.*
import dk.ku.sund.notify.config.initializeFcb
import dk.ku.sund.notify.model.Device
import org.litote.kmongo.eq
import org.litote.kmongo.lte
import org.litote.kmongo.or
import org.slf4j.LoggerFactory
import java.time.ZoneId
import java.util.*

private val logger = LoggerFactory.getLogger("MainKt")

fun main(args: Array<String>) = runBlocking {
    initializeFcb()

    // Is tomorrow a weekend? (assuming CET timezone since this is a danish study)
    val calendar = Calendar.getInstance(TimeZone.getTimeZone(ZoneId.of( "Europe/Paris" )))
    val weekend = isTomorrowWeekend(calendar)

    val collection = db.getCollection<Attendee>("attendees")

    logger.info("looking for attendees")
    val attendees = collection.find()

    attendees.publisher.consumeEach {
        logger.info("attendee: ${it.userId}")
        it.gmtOffset ?: return@consumeEach
        it.weekdayMorning ?: return@consumeEach
        it.weekendMorning ?: return@consumeEach
        if (it.devices.isEmpty()) return@consumeEach

        calendar.time = nextMorning(weekend, it)
        it.nextPush = nextPush(calendar)
        collection.updateOne(Attendee::id eq it.id, it)

        it.devices.forEach {
            logger.info("waking up: ${it.deviceId}")
            wakeUp(it)
        }
    }

    return@runBlocking
}

private fun isTomorrowWeekend(calendar: Calendar): Boolean {
    calendar.time = Date()
    val dow = calendar.get(Calendar.DAY_OF_WEEK)
    val weekend = dow == Calendar.FRIDAY || dow == Calendar.SATURDAY
    logger.debug("tomorrow is a weekend? ${weekend}")
    return weekend
}

private fun nextPush(calendar: Calendar): Date {
    val hour = calendar.get(Calendar.HOUR_OF_DAY)
    val minute = calendar.get(Calendar.MINUTE)
    calendar.time = Date()
    calendar.add(Calendar.DATE, 1)
    calendar.set(Calendar.HOUR_OF_DAY, hour)
    calendar.set(Calendar.MINUTE, minute)
    calendar.set(Calendar.SECOND, 0)
    calendar.set(Calendar.MILLISECOND, 0)
    logger.info("next push: ${calendar.time}")
    return calendar.time
}

private fun nextMorning(weekend: Boolean, attendee: Attendee): Date {
    val nextMorning: Date
    if (weekend) {
        nextMorning = attendee.weekendMorning!!
    } else {
        nextMorning = attendee.weekdayMorning!!
    }
    return nextMorning
}

private fun wakeUp(device: Device) {
    val messageBuilder = Message.builder()
    messageBuilder.setToken(device.deviceId)
    if (device.deviceType == "ios") {
        messageBuilder.setApnsConfig(
            ApnsConfig.builder()
                .setAps(
                    Aps.builder()
                        .setContentAvailable(true)
                        .build()
                ).build()
        )
    }
    val message = messageBuilder.build()
    try {
        FirebaseMessaging.getInstance().send(message)
    } catch (e: Exception) {
        logger.error("Failed sending notification to ${device.deviceId}")
    }
}
