package com.pam.ledger.web

import kotlin.random.Random

/** Plain-text participants in fictional demo events, never a feed of real matches. */
object Roster {
    enum class Sport { SOCCER, NFL, TENNIS, GOLF }

    val pool = linkedMapOf(Sport.SOCCER to 1, Sport.NFL to 1, Sport.TENNIS to 1, Sport.GOLF to 1)
    private val names = mapOf(
        Sport.SOCCER to listOf(
            "Arsenal", "Real Madrid", "Barcelona", "Liverpool", "Manchester City",
            "Bayern Munich", "Paris Saint-Germain", "Inter Milan", "AC Milan", "Juventus",
            "Borussia Dortmund", "Atletico Madrid", "Chelsea", "Tottenham Hotspur", "Ajax",
            "Benfica", "Porto", "Celtic", "Napoli", "Roma",
        ),
        Sport.NFL to listOf(
            "Arizona Cardinals", "Atlanta Falcons", "Baltimore Ravens", "Buffalo Bills",
            "Carolina Panthers", "Chicago Bears", "Cincinnati Bengals", "Cleveland Browns",
            "Dallas Cowboys", "Denver Broncos", "Detroit Lions", "Green Bay Packers",
            "Houston Texans", "Indianapolis Colts", "Jacksonville Jaguars", "Kansas City Chiefs",
            "Las Vegas Raiders", "Los Angeles Chargers", "Los Angeles Rams", "Miami Dolphins",
            "Minnesota Vikings", "New England Patriots", "New Orleans Saints", "New York Giants",
            "New York Jets", "Philadelphia Eagles", "Pittsburgh Steelers", "San Francisco 49ers",
            "Seattle Seahawks", "Tampa Bay Buccaneers", "Tennessee Titans", "Washington Commanders",
        ),
        Sport.TENNIS to listOf(
            "Jannik Sinner", "Carlos Alcaraz", "Novak Djokovic", "Alexander Zverev",
            "Daniil Medvedev", "Taylor Fritz", "Casper Ruud", "Andrey Rublev",
            "Alex de Minaur", "Holger Rune", "Stefanos Tsitsipas", "Ben Shelton",
            "Tommy Paul", "Lorenzo Musetti", "Frances Tiafoe", "Hubert Hurkacz",
            "Ugo Humbert", "Karen Khachanov", "Felix Auger-Aliassime", "Sebastian Korda",
            "Jack Draper", "Arthur Fils", "Matteo Berrettini", "Grigor Dimitrov",
        ),
        Sport.GOLF to listOf(
            "Scottie Scheffler", "Rory McIlroy", "Xander Schauffele", "Collin Morikawa",
            "Ludvig Aberg", "Viktor Hovland", "Hideki Matsuyama", "Patrick Cantlay",
            "Tommy Fleetwood", "Shane Lowry", "Justin Thomas", "Jordan Spieth",
            "Max Homa", "Wyndham Clark", "Russell Henley", "Sahith Theegala",
            "Tony Finau", "Sungjae Im", "Tom Kim", "Robert MacIntyre",
            "Matt Fitzpatrick", "Adam Scott", "Min Woo Lee", "Corey Conners",
        ),
    )
    private val events = mapOf(
        Sport.SOCCER to listOf("Demo Champions Cup", "Demo Club Challenge"),
        Sport.NFL to listOf("Demo Kickoff Classic", "Demo Gridiron Cup"),
        Sport.TENNIS to listOf("Demo Masters Final", "Demo Court Challenge"),
        Sport.GOLF to listOf("Demo Invitational, Round 2", "Demo Links Three-ball"),
    )

    internal fun participants(sport: Sport, used: Set<String>, random: Random): List<String> {
        val count = if (sport == Sport.GOLF) 3 else 2
        val available = names.getValue(sport).filterNot { it in used }
        check(available.size >= count) { "not enough unused $sport participants" }
        return available.shuffled(random).take(count)
    }

    internal fun event(sport: Sport, random: Random) = events.getValue(sport).random(random)
}
