# Chunky AutoGen (NeoForge 1.21.x)

Chaque déconnexion de joueur ajoute un cercle (rayon configurable, 2000 par défaut) à une file.
Une seule tâche Chunky tourne à la fois. La zone de déconnexion la plus récente passe en premier,
puis on remonte la liste des déconnexions précédentes.

- La génération ne tourne que si aucun joueur n'est connecté (`onlyWhenServerEmpty`). Elle se met en pause à la connexion d'un joueur et reprend là où elle en était.
- Si une déconnexion plus récente arrive pendant une génération, la zone en cours est mise en pause puis remise dans la file, et la nouvelle passe devant.
- La file et la zone en cours sont sauvegardées dans `<monde>/chunkyautogen_state.json`. Au redémarrage, la zone en cours est reprise avec `continue` de Chunky (sinon relancée, Chunky saute les chunks déjà générés). Les déconnexions causées par l'arrêt du serveur sont aussi enregistrées.

Config : `config/chunkyautogen-common.toml` (rayon, dimensions, délai avant reprise, distance anti-doublon, taille de file, pattern).
Commandes (OP) : `/chunkyautogen status`, `/chunkyautogen clear`

## Build
`./gradlew build` donne `build/libs/chunkyautogen-1.0.0.jar` (Java 21 requis, accès à maven.neoforged.net).
Déposer le jar et Chunky (version NeoForge 1.21) dans `mods/` du serveur.

Le mod appelle Chunky par réflexion (`ChunkyBridge`), il n'y a pas de dépendance de compilation sur Chunky.
