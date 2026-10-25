package com.s3vault.app.domain.semantic

import java.text.Normalizer

/**
 * CLIP's text encoder was trained mostly on English captions, so a Spanish query ("playa al atardecer") is understood much worse
 * than its English form ("beach at sunset"). This swaps the common Spanish words people use to describe photos for their English
 * equivalents, word by word. It is a convenience, not a translator: words it does not know are kept as they are (names and
 * places often work as they are), and the screen always shows the query it really ran so nothing happens out of sight.
 */
object QueryTranslator {
    private val words: Map<String, String> = mapOf(
        // animals
        "perro" to "dog", "perros" to "dogs", "perra" to "dog", "cachorro" to "puppy", "gato" to "cat", "gatos" to "cats", "gatito" to "kitten",
        "caballo" to "horse", "vaca" to "cow", "oveja" to "sheep", "pajaro" to "bird", "pajaros" to "birds", "ave" to "bird", "pez" to "fish", "peces" to "fish",
        "mariposa" to "butterfly", "conejo" to "rabbit", "tortuga" to "turtle", "elefante" to "elephant", "leon" to "lion", "tigre" to "tiger",
        // places and nature
        "playa" to "beach", "mar" to "sea", "oceano" to "ocean", "montana" to "mountain", "montanas" to "mountains", "monte" to "mountain", "nieve" to "snow",
        "bosque" to "forest", "selva" to "jungle", "rio" to "river", "lago" to "lake", "cascada" to "waterfall", "desierto" to "desert", "campo" to "countryside",
        "flor" to "flower", "flores" to "flowers", "arbol" to "tree", "arboles" to "trees", "jardin" to "garden", "cielo" to "sky", "nubes" to "clouds",
        "atardecer" to "sunset", "puesta" to "sunset", "amanecer" to "sunrise", "noche" to "night", "luna" to "moon", "estrellas" to "stars", "lluvia" to "rain",
        "ciudad" to "city", "calle" to "street", "puente" to "bridge", "edificio" to "building", "casa" to "house", "iglesia" to "church", "castillo" to "castle",
        "paisaje" to "landscape", "isla" to "island", "piscina" to "swimming pool",
        // things
        "coche" to "car", "coches" to "cars", "carro" to "car", "auto" to "car", "autos" to "cars", "moto" to "motorcycle", "bicicleta" to "bicycle", "bici" to "bicycle",
        "avion" to "airplane", "tren" to "train", "barco" to "boat", "autobus" to "bus", "camion" to "truck",
        "recibo" to "receipt", "recibos" to "receipts", "factura" to "invoice", "facturas" to "invoices", "ticket" to "receipt", "documento" to "document",
        "documentos" to "documents", "pizarra" to "whiteboard", "libro" to "book", "libros" to "books", "texto" to "text", "pantalla" to "screen",
        "captura" to "screenshot", "capturas" to "screenshots", "pasaporte" to "passport", "dni" to "identity card", "mapa" to "map", "carta" to "letter",
        "comida" to "food", "pizza" to "pizza", "pastel" to "cake", "tarta" to "cake", "cafe" to "coffee", "cerveza" to "beer", "vino" to "wine", "ensalada" to "salad",
        "fruta" to "fruit", "postre" to "dessert", "cena" to "dinner", "desayuno" to "breakfast",
        // people and events
        "persona" to "person", "personas" to "people", "gente" to "people", "nino" to "child", "nina" to "girl", "ninos" to "children", "bebe" to "baby",
        "hombre" to "man", "mujer" to "woman", "familia" to "family", "amigos" to "friends", "boda" to "wedding", "fiesta" to "party", "cumpleanos" to "birthday",
        "selfie" to "selfie", "retrato" to "portrait", "multitud" to "crowd", "concierto" to "concert", "deporte" to "sport", "futbol" to "soccer",
        "navidad" to "christmas", "vacaciones" to "vacation", "viaje" to "travel",
        // qualifiers and connectors
        "mi" to "my", "mis" to "my", "un" to "a", "una" to "a", "el" to "the", "la" to "the", "los" to "the", "las" to "the", "de" to "of", "del" to "of",
        "en" to "in", "con" to "with", "sin" to "without", "al" to "at", "y" to "and", "o" to "or", "para" to "for", "por" to "by",
        "grande" to "big", "pequeno" to "small", "rojo" to "red", "roja" to "red", "azul" to "blue", "verde" to "green", "amarillo" to "yellow", "negro" to "black",
        "blanco" to "white", "blanca" to "white", "naranja" to "orange", "rosa" to "pink", "viejo" to "old", "antiguo" to "old", "nuevo" to "new",
        "foto" to "photo", "fotos" to "photos", "imagen" to "picture", "imagenes" to "pictures",
    )

    /** What was run: the query as typed when nothing changed, or its English form, plus whether any word was replaced. */
    data class Result(val effective: String, val changed: Boolean)

    fun translate(query: String): Result {
        val tokens = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return Result("", false)
        var changed = false
        val out = tokens.map { raw ->
            val bare = raw.trim { !it.isLetterOrDigit() }
            val key = Normalizer.normalize(bare.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            val en = words[key]
            if (en != null) { changed = true; en } else raw
        }
        return Result(out.joinToString(" "), changed)
    }
}
