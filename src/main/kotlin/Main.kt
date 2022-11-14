import kotlinx.serialization.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.PairSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure

import kotlinx.serialization.json.JsonElement

import kotlinx.serialization.modules.polymorphic


class MetaMapSerializer (val json: Json) : KSerializer<Map<String, Any>> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("MetaMap") {
    }
    val listSerializer  = ListSerializer(PairSerializer(JsonElement.serializer(), JsonElement.serializer()))
    override fun deserialize( decoder: Decoder ): Map<String, Any> =

        decoder.decodeStructure( descriptor )
        {
            val listStringAny = listSerializer.deserialize(decoder)
            listStringAny.map {
                val strValue = it.second
                json.decodeFromJsonElement(String.serializer(),it.first) to
                        json.decodeFromJsonElement(PolymorphicSerializer(Any::class), strValue)

            }.toMap()

        }
    @OptIn(InternalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: Map<String, Any>) {
        encoder.encodeStructure( descriptor )
        {
            value.map {
                json.encodeToJsonElement(String.serializer(),it.key) to
                        json.encodeToJsonElement(PolymorphicSerializer(Any::class),it.value)
            }.let{
                listSerializer.serialize(encoder,it)}
        }
    }
}

@OptIn( ExperimentalSerializationApi::class )
class PolymorphicPrimitiveSerializer<T> (val typeSerializer: KSerializer<T>) : KSerializer<T>
{
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor( typeSerializer.descriptor. serialName )
    {
        element( "value", typeSerializer.descriptor )
    }
    override fun deserialize( decoder: Decoder ): T =
        decoder.decodeStructure( descriptor )
        {
            decodeElementIndex( descriptor )
            //TODO: what is this?
            decodeSerializableElement( descriptor, 0,typeSerializer)
        }
    override fun serialize(encoder: Encoder, value: T) {
        encoder.encodeStructure( descriptor )
        {

            encodeSerializableElement( descriptor, 0, typeSerializer, value )
        }
    }
}

object PairPolymorphicSerializer  : KSerializer<Pair<*,*>> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Pair") {
        element("value",PairSerializer(JsonElement.serializer(), JsonElement.serializer()).descriptor)
    }


    override fun deserialize( decoder: Decoder ): Pair<*,*> =

        decoder.decodeStructure( descriptor )
        {
            val jsonPair = decodeSerializableElement(descriptor, 0 , PairSerializer(JsonElement.serializer(), JsonElement.serializer()))
            //val discriminator = json.configuration.classDiscriminator
            val first = jsonPair.first
            val second = jsonPair.second
            //val firstTypeName = first.toString().substringAfter("\"$discriminator\":\"").substringBefore("\"")
            formatPolymorphic.decodeFromJsonElement(PolymorphicSerializer(Any::class), first) to
                    formatPolymorphic.decodeFromJsonElement(PolymorphicSerializer(Any::class), second)

        }

    override fun serialize(encoder: Encoder, value: Pair<*, *>) =
        encoder.encodeStructure( this.descriptor )
        {
            val jsonPair = formatPolymorphic.encodeToJsonElement(PolymorphicSerializer(Any::class),value.first as Any) to
                    formatPolymorphic.encodeToJsonElement(PolymorphicSerializer(Any::class),value.second as Any)
            encodeSerializableElement( descriptor, 0, PairSerializer(JsonElement.serializer(), JsonElement.serializer()), jsonPair )
        }


}

@Serializable
data class ComplexType(val i: Int,val l : Long)

@Serializable
data class OtherComplexType(val i: Int, val l: Long)
val formatPolymorphic = Json {
    serializersModule = SerializersModule {
        polymorphic(Any::class) {
            subclass(String::class, PolymorphicPrimitiveSerializer(String.serializer()))
            subclass(Int::class, PolymorphicPrimitiveSerializer(Int.serializer()))
            subclass(OtherComplexType::class, OtherComplexType.serializer())
            subclass(ComplexType::class, ComplexType.serializer())
            subclass(Pair::class, PairPolymorphicSerializer)
        }
    }
}



fun main() {
    val mapStringAny = emptyMap<String,Any>()
        .plus("complex type" to ComplexType(1, Long.MAX_VALUE))
        .plus("Int one" to 1)
        .plus("string value" to "2B||!2B")
        .plus("pair int-int" to (1 to 5))
        .plus("pair string-int" to ("assaf" to 5))
    val mmSerializer = MetaMapSerializer(formatPolymorphic)
    val jsonned = mmSerializer.json.encodeToString(mmSerializer, mapStringAny)
    val mmDecoded = mmSerializer.json.decodeFromString(mmSerializer, jsonned)
    require(mapStringAny == mmDecoded)
}
