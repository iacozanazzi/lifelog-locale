package it.iaco.lifelog

import org.junit.Assert.assertEquals
import org.junit.Test

class SfondoTest {
    @Test fun musicaSenzaParlatoSiSalta() = assertEquals(Sfondo.MUSICA, Sfondo.decide(tv = 0.05f, music = 0.8f, speech = 0.1f))
    @Test fun canzoneCantataConParlatoNonSiSalta() = assertEquals(Sfondo.NO, Sfondo.decide(tv = 0.05f, music = 0.6f, speech = 0.5f))
    @Test fun televisione() = assertEquals(Sfondo.TV, Sfondo.decide(tv = 0.3f, music = 0.1f, speech = 0.7f))
    @Test fun conversazioneNormale() = assertEquals(Sfondo.NO, Sfondo.decide(tv = 0.05f, music = 0.02f, speech = 0.9f))
}
