# protege-mcp

Serveur MCP intégré à Protégé Desktop 5.6. Il agit sur l'ontologie ouverte dans la fenêtre : les
modifications apparaissent tout de suite dans l'interface et chaque appel d'édition est une seule
étape d'annulation. Rien n'est écrit sur le disque avant `save_ontology`.

Il ne dépend que de Protégé : pas de Maven, ni de bibliothèque tierce.

## Installation

    ./build.sh install

Cette commande copie le plugin dans `~/.Protege/plugins` et crée `~/.Protege/mcp/config.properties`
(port et token, droits 600). Relance ensuite Protégé. Le serveur écoute sur
`http://127.0.0.1:8765/mcp` tant qu'une fenêtre Protégé est ouverte.

Enregistrement dans Claude Code :

    TOKEN=$(sed -n 's/^token=//p' ~/.Protege/mcp/config.properties)
    claude mcp add --transport http -s user protege http://127.0.0.1:8765/mcp \
        --header "Authorization: Bearer $TOKEN"

Si `PROTEGE_HOME` diffère de `/opt/Protege-5.6.9`, définis cette variable avant le build. Pour
changer de port, modifie `port=` dans le fichier de configuration.

## Outils

| Groupe | Outils |
| --- | --- |
| Contexte | `status`, `ontology_info`, `show_entity`, `set_active_ontology` |
| Lecture | `search_entities`, `describe_entity`, `get_hierarchy`, `list_axioms` |
| Requêtes | `dl_query`, `sparql_query` |
| Raisonnement | `classify`, `explain`, `what_if`, `audit` |
| Édition | `add_axioms`, `remove_axioms`, `create_entities`, `rename_entity`, `delete_entities`, `undo`, `redo` |
| Fichiers | `open_ontology`, `new_ontology`, `save_ontology` |

- `describe_entity` sans argument décrit l'entité sélectionnée dans l'interface.
- `what_if` simule un changement sur une copie privée et rapporte les classes devenues
  insatisfiables, l'incohérence et les subsomptions gagnées ou perdues.
- `explain` cherche les ensembles minimaux d'axiomes (justifications) derrière une inférence, une
  classe insatisfiable ou une incohérence.
- `add_axioms` déclare les nouvelles entités et les liste dans `new_entities`, ce qui révèle les
  fautes de frappe. `dry_run` est disponible.
- Une entité se désigne par IRI, nom préfixé, nom court ou label.

## Limites

- `undo` partage la pile de Protégé : il peut annuler une modification faite à la main.
- `sparql_query` lit une copie des axiomes déclarés, sans inférences, et recharge cette copie à
  chaque requête. Il utilise directement le moteur Sesame de `rdf-library`, car `OwlTripleStore`
  échoue avec `NoClassDefFoundError` sur Guava dans Protégé 5.6.9.
- La syntaxe `manchester` de `add_axioms` couvre les axiomes de classe. Les autres passent par la
  syntaxe fonctionnelle.
- `dl_query` et `describe_entity` s'exécutent sur le thread Swing. Une requête lourde fige
  l'interface le temps du calcul.
- Une ontologie incohérente fait ouvrir par Protégé sa boîte de dialogue habituelle après `classify`.
- `explain` part du raisonneur courant. ELK ne gère pas toutes les inférences, préfère HermiT.

## Sécurité

Le serveur n'écoute que sur la boucle locale, exige le token, et refuse tout `Host` ou `Origin`
étranger pour bloquer le DNS rebinding. Un détenteur du token peut lire et écrire des fichiers avec
tes droits, via `open_ontology` et `save_ontology`. Garde `config.properties` privé.

## Test

Avec Protégé ouvert (le test ouvre `test/pizza.ofn` dans une nouvelle fenêtre) :

    python3 test/smoke_test.py
