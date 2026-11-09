package sharing

import (
	"context"
	"strings"

	"notes/server/internal/spaces"
)

// Trait is the shared trait (§7.8, §7.9) as viewerID sees it: on while a
// space has someone else in it or invited, naming them.
func (s *Service) Trait(viewerID int64) spaces.TraitSource {
	return func(ctx context.Context, ids []string) (map[string]spaces.Trait, error) {
		out := map[string]spaces.Trait{}
		for _, id := range ids {
			if role, err := s.Role(ctx, viewerID, id); err != nil {
				return nil, err
			} else if role == "" {
				continue
			}
			members, err := s.members(ctx, id)
			if err != nil {
				return nil, err
			}
			invited, err := s.invitees(ctx, id)
			if err != nil {
				return nil, err
			}
			var with, pending, audience []string
			for _, p := range members {
				if p.ID != viewerID {
					with = append(with, p.Name())
					audience = append(audience, UserRef(p.ID))
				}
			}
			for _, in := range invited {
				if in.UserID != viewerID {
					pending = append(pending, in.Person().Name())
					audience = append(audience, UserRef(in.UserID))
				}
			}
			if len(with) == 0 && len(pending) == 0 {
				continue
			}
			var summary []string
			if len(with) > 0 {
				summary = append(summary, "With "+names(with))
			}
			if len(pending) > 0 {
				word := "Invited "
				if len(with) > 0 {
					word = "invited "
				}
				summary = append(summary, word+names(pending))
			}
			out[id] = spaces.Trait{
				Label:    "Shared",
				Summary:  strings.Join(summary, " · "),
				Exposure: "Visible to " + names(append(with, pending...)),
				Audience: audience,
			}
		}
		return out, nil
	}
}

// names lists people as a sentence does: "Ana", "Ana and Ben", "Ana, Ben and Cy".
func names(list []string) string {
	switch len(list) {
	case 0:
		return ""
	case 1:
		return list[0]
	}
	return strings.Join(list[:len(list)-1], ", ") + " and " + list[len(list)-1]
}
